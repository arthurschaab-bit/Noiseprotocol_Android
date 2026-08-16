package com.example.lrmprotokoll.audio

import android.content.Context
import android.util.Log
import com.example.lrmprotokoll.data.AppDatabase
import com.example.lrmprotokoll.data.SettingsManager
import org.tensorflow.lite.task.audio.classifier.AudioClassifier
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class NoiseClassifier(private val context: Context) {

    private var classifier: AudioClassifier? = null
    private val settingsManager = SettingsManager(context)

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
            classifier = AudioClassifier.createFromFile(context, "yamnet.tflite")
        } catch (e: Exception) {
            Log.e("NoiseClassifier", "Error loading model: ${e.message}")
        }
    }

    fun classify(file: File): String? {
        val currentLabels = classifyDetailed(file) ?: return null
        
        // 1. Abgleich mit der Datenbank bekannter Geräusche
        try {
            val db = AppDatabase.getDatabase(context)
            val references = runBlocking { db.noiseDao().getAllReferences().first() }
            
            references.forEach { ref ->
                val refPattern = ref.pattern.split(",").toSet()
                val intersection = currentLabels.intersect(refPattern)
                if (intersection.size.toFloat() / refPattern.size > 0.5f) {
                    return "Gelernt: ${ref.name}"
                }
            }
        } catch (e: Exception) {
            Log.e("NoiseClassifier", "Error checking references: ${e.message}")
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

            val tensor = currentClassifier.createInputTensorAudio()
            tensor.load(shortArray)
            
            val results = currentClassifier.classify(tensor)
            
            return results.flatMap { it.categories }
                .filter { it.score > settingsManager.aiConfidenceThreshold }
                .sortedByDescending { it.score }
                .map { it.label }
                .distinct()
        } catch (e: Exception) {
            Log.e("NoiseClassifier", "Error during detailed classification: ${e.message}")
            return null
        }
    }
}
