package com.example.lrmprotokoll.audio

import android.content.Context
import android.util.Log
import com.example.lrmprotokoll.LaermprotokollApp
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier
import com.google.mediapipe.tasks.audio.core.RunningMode
import com.google.mediapipe.tasks.components.containers.AudioData
import com.google.mediapipe.tasks.core.BaseOptions
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private const val TAG = "NoiseClassifier"
private const val MODEL_ASSET = "yamnet.tflite"

/**
 * Klassifiziert Geraeusche mit YAMNet ueber MediaPipe Tasks Audio (B-11: Nachfolger fuer das
 * abgekuendigte, nicht 16-KB-seitenausgerichtete org.tensorflow:tensorflow-lite-task-audio).
 * app/src/main/assets/yamnet.tflite wird unveraendert weiterverwendet - MediaPipe Tasks ist der
 * direkte Nachfolger der TFLite-Task-API und erwartet dieselbe Modell-Metadata.
 *
 * Faengt bewusst `Throwable` statt nur `Exception`: ein fehlgeschlagenes Laden einer nativen
 * Bibliothek wirft `UnsatisfiedLinkError`, ein `Error`, kein `Exception`. Ohne diese Absicherung
 * wuerde der Konstruktor durchschlagen und [AudioRecordingService.onCreate] den gesamten
 * Foreground Service mitreissen (Prompt B-11, Problem 2). Beim jetzigen Migrationsziel laedt
 * MediaPipe seine native Bibliothek zwar selbst schon robuster, aber die Absicherung kostet
 * nichts und schuetzt gegen die naechste native Abhaengigkeit.
 */
class NoiseClassifier(private val context: Context) : SoundClassifier {

    private val container get() = (context.applicationContext as LaermprotokollApp).container

    private var classifier: AudioClassifier? = null
    private val settingsManager = container.settingsManager

    private val labelMapping = mapOf(
        "Hammer" to "Hämmern",
        "Hammering" to "Hämmern",
        "Drill" to "Bohren",
        "Drilling" to "Bohren",
        "Excavator" to "Bagger",
        "Machinery" to "Bagger",
        "Heavy machinery" to "Bagger",
        "Construction" to "Baustelle",
        "Vehicle" to "Verkehr",
        "Traffic noise" to "Verkehr",
        "Car" to "Verkehr",
        "Truck" to "Verkehr",
        "Engine" to "Motor",
        "Siren" to "Sirene",
        "Beep" to "Piepen",
        "Lawn mower" to "Rasenmäher",
        "Saw" to "Säge",
        "Wood" to "Holzarbeiten",
        "Explosion" to "Knall"
    )

    init {
        try {
            val baseOptions = BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build()
            val options = AudioClassifier.AudioClassifierOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.AUDIO_CLIPS)
                .build()
            classifier = AudioClassifier.createFromOptions(context, options)
        } catch (e: Throwable) {
            Log.e(TAG, "Error loading model: ${e.message}", e)
        }
    }

    override fun classify(file: File): String? {
        val currentLabels = classifyDetailed(file) ?: return null

        // 1. Abgleich mit der Datenbank bekannter Geräusche
        try {
            val references = runBlocking { container.database.noiseDao().getAllReferences().first() }

            references.forEach { ref ->
                val refPattern = ref.pattern.split(",").toSet()
                val intersection = currentLabels.intersect(refPattern)
                if (intersection.size.toFloat() / refPattern.size > 0.5f) {
                    return "Gelernt: ${ref.name}"
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error checking references: ${e.message}", e)
        }

        // 2. Standard-Top-Label finden
        val top = currentLabels.firstOrNull {
            it != "Background noise" && it != "Silence" && it != "Noise"
        }

        return labelMapping[top] ?: top
    }

    fun classifyDetailed(file: File): List<String>? {
        val currentClassifier = classifier ?: return null

        try {
            val fileLength = file.length().toInt()
            if (fileLength < 44) return null

            val pcmLength = fileLength - 44
            val shortCount = pcmLength / 2
            val shortArray = ShortArray(shortCount)

            FileInputStream(file).use { fis ->
                fis.skip(44)
                val byteBuffer = ByteBuffer.allocate(pcmLength).order(ByteOrder.LITTLE_ENDIAN)
                fis.read(byteBuffer.array())
                byteBuffer.asShortBuffer().get(shortArray)
            }

            // AudioData ist ein Ringpuffer, der genau auf den kompletten Ausschnitt dimensioniert
            // wird: der Klassifikator zerlegt ihn intern selbst in Fenster und liefert pro
            // Fenster ein eigenes ClassificationResult (anders als die alte TensorAudio-basierte
            // API, deren fest auf die Modellgroesse dimensionierter Ringpuffer bei einem
            // laengeren Clip nur den letzten Ausschnitt behielt).
            val format = AudioData.AudioDataFormat.builder()
                .setNumOfChannels(1)
                .setSampleRate(settingsManager.audioSampleRate.toFloat())
                .build()
            val audioData = AudioData.create(format, shortArray.size)
            audioData.load(shortArray)

            val result = currentClassifier.classify(audioData)

            return result.classificationResults()
                .flatMap { it.classifications() }
                .flatMap { it.categories() }
                .filter { it.score() > settingsManager.aiConfidenceThreshold }
                .sortedByDescending { it.score() }
                .map { it.categoryName() }
                .distinct()
        } catch (e: Throwable) {
            Log.e(TAG, "Error during detailed classification: ${e.message}", e)
            return null
        }
    }
}
