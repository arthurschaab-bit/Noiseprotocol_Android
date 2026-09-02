package com.example.lrmprotokoll.audio

import android.content.Context
import android.util.Log
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.data.KlassifikationsRohdaten
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifierResult
import com.google.mediapipe.tasks.audio.core.RunningMode
import com.google.mediapipe.tasks.components.containers.AudioData
import com.google.mediapipe.tasks.core.BaseOptions
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private const val TAG = "NoiseClassifier"
private const val MODEL_ASSET = "yamnet.tflite"
private const val WAV_HEADER_SIZE = 44
private val PLAUSIBLE_SAMPLE_RATES = 8000..192000

/**
 * KI-Umbau Etappe 2.6: bis Etappe 1 war dies die einstellbare "Einheitsschwelle"
 * (`SettingsManager.aiConfidenceThreshold`), die zugleich entschied, ob UEBERHAUPT ein Label
 * gezeigt wurde. Diese Rolle uebernimmt jetzt die Hysterese auf den Baulärm-Gruppen-Score
 * ([BaulaermKonfiguration]). Der einzige verbleibende Zweck dieser Konstante ist, `topKlassen`
 * (Anzeige/Referenzmuster-Abgleich, siehe [KlassifikationsRohdaten]-KDoc) nicht mit allen 521
 * Kategorien jedes Frames zu fuellen - unveraendert der alte Default-Wert, damit sich das
 * Referenzmuster-Abgleichsverhalten aus Etappe 1 nicht unbeabsichtigt mitaendert.
 */
private const val TOP_KLASSEN_KANDIDATEN_SCHWELLE = 0.3f

/**
 * KI-Umbau Etappe 2.2: gegen die tatsaechliche, im Modell eingebettete `yamnet_label_list.txt`
 * verifiziert (nicht angenommen - siehe `LabelMappingValidierungTest`). Von den urspruenglich 19
 * Eintraegen aus Etappe 1 waren 9, nicht wie dort vermerkt 10, tote Eintraege (die
 * Etappe-1-KDoc-Zahl war ungenau - hier korrigiert): "Hammering"/"Drilling" waren reine
 * Duplikate der bereits vorhandenen "Hammer"/"Drill"-Eintraege und entfallen ersatzlos;
 * "Excavator"/"Machinery"/"Heavy machinery"/"Construction" haben keinerlei Entsprechung in den
 * 521 YAMNet-Klassen und entfallen ebenfalls; "Traffic noise", "Beep" und "Saw" waren knapp
 * daneben (der Modellname lautet exakt "Traffic noise, roadway noise", "Beep, bleep" bzw.
 * "Sawing") und wurden auf den exakten Namen korrigiert statt entfernt.
 *
 * `internal` auf Modulebene statt `private` in [NoiseClassifier] (Etappe 1.4-Muster wie
 * [ROHDATEN_KLASSEN_INDIZES]): so ist die Tabelle ohne echten Klassifikator/Context testbar.
 */
internal val labelMapping = mapOf(
    "Hammer" to "Hämmern",
    "Drill" to "Bohren",
    "Vehicle" to "Verkehr",
    "Traffic noise, roadway noise" to "Verkehr",
    "Car" to "Verkehr",
    "Truck" to "Verkehr",
    "Engine" to "Motor",
    "Siren" to "Sirene",
    "Beep, bleep" to "Piepen",
    "Lawn mower" to "Rasenmäher",
    "Sawing" to "Säge",
    "Wood" to "Holzarbeiten",
    "Explosion" to "Knall",
)

/**
 * KI-Umbau Etappe 1.4: nominale Fenster-Dauer des YAMNet-Modells (960 ms) - aus der oeffentlichen
 * YAMNet-Dokumentation uebernommen, NICHT gegen diese exakte MediaPipe-Einbindung auf einem
 * Geraet verifiziert (kein Geraet/Mikrofon in dieser Entwicklungsumgebung verfuegbar). Die
 * Hop-Dauer ([KlassifikationsRohdaten.frameHopMs]) wird dagegen empirisch aus den tatsaechlichen
 * Frame-Zeitstempeln berechnet, ist also kein Wert vom Hoerensagen - siehe [buildeRohdatenBauplan].
 * Owner-Aufgabe aus Etappe 1.1: die hier geloggten Diagnosewerte auf einem echten Geraet pruefen.
 */
internal const val YAMNET_NOMINELLE_FRAME_DAUER_MS = 960

/**
 * Liest die Abtastrate direkt aus dem WAV-Header (Offset 24, 4 Byte little-endian - siehe
 * [AudioRecordingService.writeWavHeader]) statt sie aus der AKTUELLEN Einstellung zu raten.
 * Review-Befund aus PR #19: die Datei kann zu einem frueheren Zeitpunkt mit einer anderen
 * Abtastrate aufgenommen worden sein als der, die [SettingsManager.audioSampleRate] gerade
 * zurueckgibt - z.B. beim nachtraeglichen "Als Referenz lernen" auf eine alte Aufnahme, nachdem
 * die Einstellung zwischenzeitlich geaendert wurde. Faellt auf [fallback] zurueck, wenn der
 * Header zu kurz ist oder einen unplausiblen Wert enthaelt.
 *
 * `internal` statt `private`, damit die Kernlogik ohne echten Klassifikator/Context testbar ist.
 */
internal fun wavSampleRateOrFallback(header: ByteArray, fallback: Int): Int {
    if (header.size < WAV_HEADER_SIZE) return fallback
    val rate = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(24)
    return if (rate in PLAUSIBLE_SAMPLE_RATES) rate else fallback
}

/**
 * KI-Umbau Etappe 1.1 (Diagnose): liest die Bit-Tiefe aus dem WAV-Header (Offset 34, 2 Byte
 * little-endian) - Teil der in Auftrag Abschnitt 1.1 geforderten Diagnosewerte
 * ("beim Klassifizieren: logge die aus dem WAV-Header gelesene Rate und die Bit-Tiefe").
 * `null` bei zu kurzem Header statt eines geratenen Werts.
 */
internal fun wavBitsPerSample(header: ByteArray): Int? {
    if (header.size < WAV_HEADER_SIZE) return null
    return ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getShort(34).toInt()
}

/**
 * KI-Umbau Etappe 1.4: alles, was noetig ist, um einen [KlassifikationsRohdaten]-Datensatz
 * anzulegen, AUSSER `recordId` - die kennt [NoiseClassifier] selbst nicht (beim
 * Online-Klassifizieren existiert der zugehoerige [com.example.lrmprotokoll.data.NoiseRecord]
 * zum Zeitpunkt der Inferenz noch gar nicht, siehe [AudioRecordingService]). Der Aufrufer ruft
 * [mitRecordId] auf, sobald die Aufnahme gespeichert ist und ihre Id kennt.
 */
data class RohdatenBauplan(
    val modellVersion: String,
    val klassifiziertAm: Long,
    val frameAnzahl: Int,
    val frameDauerMs: Int,
    val frameHopMs: Int,
    val klassenIndizes: IntArray,
    val frameScores: ByteArray,
    val topKlassen: String,
    val impulsCrest: Float? = null,
    val impulsKurtosis: Float? = null,
    val impulsWiederholrateHz: Float? = null,
    val impulsPeakSchaerfe: Float? = null,
    val impulsMittlererPegel: Float? = null,
) {
    fun mitRecordId(recordId: Long): KlassifikationsRohdaten = KlassifikationsRohdaten(
        recordId = recordId,
        modellVersion = modellVersion,
        klassifiziertAm = klassifiziertAm,
        frameAnzahl = frameAnzahl,
        frameDauerMs = frameDauerMs,
        frameHopMs = frameHopMs,
        klassenIndizes = klassenIndizes,
        frameScores = frameScores,
        topKlassen = topKlassen,
        impulsCrest = impulsCrest,
        impulsKurtosis = impulsKurtosis,
        impulsWiederholrateHz = impulsWiederholrateHz,
        impulsPeakSchaerfe = impulsPeakSchaerfe,
        impulsMittlererPegel = impulsMittlererPegel,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RohdatenBauplan) return false
        return modellVersion == other.modellVersion && klassifiziertAm == other.klassifiziertAm &&
            frameAnzahl == other.frameAnzahl && frameDauerMs == other.frameDauerMs &&
            frameHopMs == other.frameHopMs && klassenIndizes.contentEquals(other.klassenIndizes) &&
            frameScores.contentEquals(other.frameScores) && topKlassen == other.topKlassen &&
            impulsCrest == other.impulsCrest && impulsKurtosis == other.impulsKurtosis &&
            impulsWiederholrateHz == other.impulsWiederholrateHz &&
            impulsPeakSchaerfe == other.impulsPeakSchaerfe && impulsMittlererPegel == other.impulsMittlererPegel
    }

    override fun hashCode(): Int {
        var result = modellVersion.hashCode()
        result = 31 * result + klassifiziertAm.hashCode()
        result = 31 * result + frameAnzahl
        result = 31 * result + frameDauerMs
        result = 31 * result + frameHopMs
        result = 31 * result + klassenIndizes.contentHashCode()
        result = 31 * result + frameScores.contentHashCode()
        result = 31 * result + topKlassen.hashCode()
        result = 31 * result + (impulsCrest?.hashCode() ?: 0)
        result = 31 * result + (impulsKurtosis?.hashCode() ?: 0)
        result = 31 * result + (impulsWiederholrateHz?.hashCode() ?: 0)
        result = 31 * result + (impulsPeakSchaerfe?.hashCode() ?: 0)
        result = 31 * result + (impulsMittlererPegel?.hashCode() ?: 0)
        return result
    }
}

/** KI-Umbau Etappe 1.4: Ergebnis einer Klassifizierung mit Rohdaten fuer die Persistenz. */
data class KlassifikationsErgebnis(val label: String?, val rohdaten: RohdatenBauplan)

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
 *
 * KI-Umbau Etappe 1.5: die eigentliche Label-Entscheidung liegt nicht mehr hier, sondern in der
 * reinen Funktion [leiteLabelAb] - diese Klasse liefert ihr nur noch die Rohdaten (per Inferenz
 * frisch berechnet in [classify]/[klassifiziereMitRohdaten], oder per [leiteLabelAb] direkt aus
 * der DB fuer "Neu bewerten").
 */
class NoiseClassifier(private val context: Context) : SoundClassifier, RohdatenClassifier {

    private val container get() = (context.applicationContext as LaermprotokollApp).container

    private var classifier: AudioClassifier? = null
    private val settingsManager = container.settingsManager

    /**
     * Dateiname + CRC32-Hash der eingebetteten `yamnet.tflite` (KI-Umbau Etappe 1.4), einmalig
     * beim Anlegen berechnet und danach unveraendert - falls sich das Modell-Asset zwischen
     * App-Versionen aendert, ist das an gespeicherten [KlassifikationsRohdaten] erkennbar,
     * ohne dass sich der Hash bei jedem Aufruf neu berechnen muss (~4 MB Datei).
     */
    private val modellVersion: String by lazy {
        try {
            val crc = CRC32()
            context.assets.open(MODEL_ASSET).use { stream ->
                val puffer = ByteArray(8192)
                var gelesen = stream.read(puffer)
                while (gelesen >= 0) {
                    crc.update(puffer, 0, gelesen)
                    gelesen = stream.read(puffer)
                }
            }
            "$MODEL_ASSET@${crc.value.toString(16)}"
        } catch (e: Throwable) {
            Log.w(TAG, "Konnte Modell-Hash nicht berechnen: ${e.message}")
            MODEL_ASSET
        }
    }

    init {
        try {
            val baseOptions = BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build()
            val options = AudioClassifier.AudioClassifierOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.AUDIO_CLIPS)
                .build()
            classifier = AudioClassifier.createFromOptions(context, options)
            container.diagnosticsReporter.breadcrumb("AI", "YAMNet-Klassifikator erfolgreich initialisiert")
        } catch (e: Throwable) {
            Log.e(TAG, "Error loading model: ${e.message}", e)
            container.diagnosticsReporter.report(
                code = com.example.lrmprotokoll.diagnose.DiagnosticCode.AI_MODEL_INIT_FAILED,
                component = "NoiseClassifier",
                operation = "init",
                severity = com.example.lrmprotokoll.diagnose.DiagnosticSeverity.ERROR,
                cause = e,
                message = "YAMNet Modell konnte nicht geladen werden: ${e.message}"
            )
        }
    }

    data class ScoredCategory(val name: String, val score: Float)

    /**
     * Baut die [AbleitungsKonfiguration] aus dem aktuellen Live-Zustand (Referenzmuster aus der
     * DB, das bestehende [labelMapping]). Fehler beim Lesen der Referenzen werden - wie in der
     * Implementierung vor Etappe 1.5 - abgefangen und fuehren zu einer leeren Referenzliste
     * (fuehrt direkt zum Standard-Top-Label-Pfad), NICHT zu einem Abbruch der Klassifizierung.
     */
    /**
     * KI-Umbau Etappe 1.5: `internal` statt `private`, damit "Neu bewerten" (MainActivity.kt)
     * dieselbe Konfiguration verwenden kann wie eine frische Klassifizierung - sonst koennten
     * beide Pfade unbemerkt auseinanderlaufen (z.B. unterschiedliche Referenzmuster-Stichproben).
     */
    internal fun aktuelleKonfiguration(): AbleitungsKonfiguration {
        val referenzen = try {
            runBlocking { container.database.noiseDao().getAllReferences().first() }
        } catch (e: Throwable) {
            Log.e(TAG, "Error checking references: ${e.message}", e)
            emptyList()
        }
        val baulaermKonfiguration = BaulaermKonfiguration(
            einSchwelle = settingsManager.aiEinSchwelle,
            ausSchwelle = settingsManager.aiAusSchwelle,
        )
        return AbleitungsKonfiguration(
            referenzMuster = referenzen,
            labelMapping = labelMapping,
            baulaermKonfiguration = baulaermKonfiguration,
        )
    }

    override fun classify(file: File): String? {
        val bauplan = buildeRohdatenBauplan(file) ?: return null
        val rohdaten = bauplan.mitRecordId(recordId = -1)
        val konfiguration = aktuelleKonfiguration()
        return formatiereBaulaermBefund(leiteLabelAb(rohdaten, konfiguration), konfiguration.labelMapping)
    }

    /**
     * KI-Umbau Etappe 1.4: wie [classify], liefert aber zusaetzlich den [RohdatenBauplan] fuer
     * die Persistenz - genutzt vom Online- (Etappe 1.3) und Batch-Klassifizierungspfad, damit
     * dort nicht zweimal (einmal fuer das Label, einmal fuer die Rohdaten) inferenziert wird.
     */
    override fun klassifiziereMitRohdaten(file: File): KlassifikationsErgebnis? {
        val bauplan = buildeRohdatenBauplan(file) ?: return null
        val konfiguration = aktuelleKonfiguration()
        val befund = leiteLabelAb(bauplan.mitRecordId(recordId = -1), konfiguration)
        val label = formatiereBaulaermBefund(befund, konfiguration.labelMapping)
        return KlassifikationsErgebnis(label, bauplan)
    }

    fun classifyDetailed(file: File): List<String>? = classifyDetailedScored(file)?.map { it.name }

    fun classifyDetailedScored(file: File): List<ScoredCategory>? {
        val gelesen = leseUndKlassifiziere(file) ?: return null
        return extrahiereTopKategorien(gelesen.result, TOP_KLASSEN_KANDIDATEN_SCHWELLE)
    }

    /**
     * KI-Umbau Etappe 3.4: buendelt das MediaPipe-Ergebnis mit dem ROHEN (nicht
     * peak-normalisierten) Sample-Puffer und der tatsaechlich verwendeten Abtastrate - die
     * Impulsanalyse braucht das echte, unveraenderte Signal, die Peak-Normalisierung aus
     * Etappe 1.6 wuerde die fuer [ImpulsMerkmale.mittlererPegel] noetigen relativen
     * Pegelunterschiede zwischen Aufnahmen sonst wegnivellieren.
     */
    private data class GeleseneKlassifizierung(
        val result: AudioClassifierResult,
        val rohePuffer: ShortArray,
        val sampleRate: Int,
    )

    /**
     * KI-Umbau Etappe 1.4: liest die WAV-Datei und fuehrt EINE Inferenz aus - gemeinsame
     * Grundlage sowohl fuer [classifyDetailedScored] als auch [buildeRohdatenBauplan]. Fehler
     * werden hier bewusst NICHT geloggt/gemeldet (anders als vorher inline) - das bleibt Sache
     * des jeweiligen Aufrufers, damit die Diagnose-Meldung weiterhin den richtigen `operation`-
     * Namen traegt (z.B. "classifyDetailed" vs. ein neuer Rohdaten-Kontext).
     */
    private fun leseUndKlassifiziere(file: File): GeleseneKlassifizierung? {
        val currentClassifier = classifier ?: return null

        val fileLength = file.length().toInt()
        if (fileLength < WAV_HEADER_SIZE) return null

        val pcmLength = fileLength - WAV_HEADER_SIZE
        val shortCount = pcmLength / 2
        val shortArray = ShortArray(shortCount)
        val header = ByteArray(WAV_HEADER_SIZE)

        FileInputStream(file).use { fis ->
            fis.read(header)
            val byteBuffer = ByteBuffer.allocate(pcmLength).order(ByteOrder.LITTLE_ENDIAN)
            fis.read(byteBuffer.array())
            byteBuffer.asShortBuffer().get(shortArray)
        }

        val sampleRate = wavSampleRateOrFallback(header, settingsManager.audioSampleRate)
        val headerRate = wavSampleRateOrFallback(header, -1)
        val bitTiefe = wavBitsPerSample(header)
        Log.i(
            TAG,
            "KI-Diagnose: WAV-Header-Rate=$headerRate (verwendet=$sampleRate), Bit-Tiefe=$bitTiefe",
        )
        // Nachtrag zu Etappe 1.1: zusaetzlich als Breadcrumb, damit die Werte im
        // Support-Bundle-Export landen (Logcat ist ohne Rechner nicht einsehbar).
        container.diagnosticsReporter.breadcrumb(
            "AI",
            "WAV-Header geprueft (rate=$headerRate, verwendet=$sampleRate, bitTiefe=$bitTiefe)",
            data = mapOf("headerRate" to headerRate, "verwendeteRate" to sampleRate, "bitTiefe" to bitTiefe),
        )

        // KI-Umbau Etappe 1.6: NUR der Inferenz-Puffer wird normalisiert - die oben bereits
        // fertig gelesene shortArray-Kopie ist ohnehin schon getrennt von der auf der SD-Karte
        // gespeicherten WAV-Datei (die wird hier nur gelesen, nicht verändert).
        val inferenzPuffer = if (settingsManager.aiNormalisierung) normalisierePeak(shortArray) else shortArray

        // AudioData ist ein Ringpuffer, der genau auf den kompletten Ausschnitt dimensioniert
        // wird: der Klassifikator zerlegt ihn intern selbst in Fenster und liefert pro
        // Fenster ein eigenes ClassificationResult (anders als die alte TensorAudio-basierte
        // API, deren fest auf die Modellgroesse dimensionierter Ringpuffer bei einem
        // laengeren Clip nur den letzten Ausschnitt behielt).
        val format = AudioData.AudioDataFormat.builder()
            .setNumOfChannels(1)
            .setSampleRate(sampleRate.toFloat())
            .build()
        val audioData = AudioData.create(format, inferenzPuffer.size)
        audioData.load(inferenzPuffer)

        val result = currentClassifier.classify(audioData) ?: return null
        return GeleseneKlassifizierung(result, shortArray, sampleRate)
    }

    /**
     * KI-Umbau Etappe 1.4: baut den [RohdatenBauplan] aus einer frischen Inferenz - Frame-Scores
     * fuer die feste Klassen-Untermenge [ROHDATEN_KLASSEN_INDIZES] UND die Top-Kategorien fuer
     * die Anzeige/den Referenzabgleich ([topKlassen]), beides aus demselben MediaPipe-Ergebnis,
     * damit nicht zweimal inferenziert werden muss.
     */
    private fun buildeRohdatenBauplan(file: File): RohdatenBauplan? {
        try {
            val gelesen = leseUndKlassifiziere(file) ?: return null
            val result = gelesen.result
            val topKategorien = extrahiereTopKategorien(result, TOP_KLASSEN_KANDIDATEN_SCHWELLE)

            // KI-Umbau Etappe 3.4: dieselbe Inferenz-Runde liefert zusaetzlich die physikalischen,
            // YAMNet-unabhaengigen Huellkurven-Merkmale - kein zweiter Datei-/Inferenzzugriff.
            val impulsMerkmale = berechneImpulsMerkmale(gelesen.rohePuffer, gelesen.sampleRate)

            val frames = result.classificationResults()
            val zeitstempel = frames.map { it.timestampMs().orElse(0L) }
            val klassenIndizes = ROHDATEN_KLASSEN_INDIZES
            val frameScores = ByteArray(frames.size * klassenIndizes.size)

            frames.forEachIndexed { frameIndex, frame ->
                val kategorienDiesesFrames = frame.classifications().firstOrNull()?.categories().orEmpty()
                val scoreProIndex = HashMap<Int, Float>(kategorienDiesesFrames.size)
                kategorienDiesesFrames.forEach { scoreProIndex[it.index()] = it.score() }
                klassenIndizes.forEachIndexed { klassenPosition, modellIndex ->
                    val score = scoreProIndex[modellIndex] ?: 0f
                    val quantisiert = (score * 255f).roundToInt().coerceIn(0, 255)
                    frameScores[frameIndex * klassenIndizes.size + klassenPosition] = quantisiert.toByte()
                }
            }

            val frameHopMs = if (zeitstempel.size >= 2) (zeitstempel[1] - zeitstempel[0]).toInt() else 0

            // Nachtrag zu Etappe 1.4: bestaetigt im Support-Bundle-Export, dass pro Aufnahme
            // tatsaechlich ein Rohdaten-Bauplan entsteht (Frame-Anzahl > 0, Modellversion
            // gesetzt) - unabhaengig davon, ob AudioRecordingService ihn erfolgreich in Room
            // persistiert (das bestaetigt die separate "NoiseRecord gespeichert"-Breadcrumb).
            container.diagnosticsReporter.breadcrumb(
                "AI",
                "Rohdaten-Bauplan erstellt (frames=${frames.size}, hopMs=$frameHopMs, topKlassen=${topKategorien.size})",
                data = mapOf(
                    "frameAnzahl" to frames.size,
                    "frameHopMs" to frameHopMs,
                    "modellVersion" to modellVersion,
                    "topKlassenAnzahl" to topKategorien.size,
                ),
            )

            return RohdatenBauplan(
                modellVersion = modellVersion,
                klassifiziertAm = System.currentTimeMillis(),
                frameAnzahl = frames.size,
                frameDauerMs = YAMNET_NOMINELLE_FRAME_DAUER_MS,
                frameHopMs = frameHopMs,
                klassenIndizes = klassenIndizes,
                frameScores = frameScores,
                topKlassen = kodiereTopKlassen(topKategorien),
                impulsCrest = impulsMerkmale.crest,
                impulsKurtosis = impulsMerkmale.kurtosis,
                impulsWiederholrateHz = impulsMerkmale.wiederholrateHz,
                impulsPeakSchaerfe = impulsMerkmale.peakSchaerfe,
                impulsMittlererPegel = impulsMerkmale.mittlererPegel,
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Error during raw classification: ${e.message}", e)
            container.diagnosticsReporter.report(
                code = com.example.lrmprotokoll.diagnose.DiagnosticCode.AI_INFERENCE_FAILED,
                component = "NoiseClassifier",
                operation = "buildeRohdatenBauplan",
                severity = com.example.lrmprotokoll.diagnose.DiagnosticSeverity.WARN,
                cause = e,
                message = "Inferenz auf Audiodatei fehlgeschlagen: ${e.message}"
            )
            return null
        }
    }

    companion object {
        /**
         * Fasst die MediaPipe-Kategorien ueber ALLE Frames eines Clips zusammen (nicht nur
         * eines einzelnen Fensters): score-absteigend sortiert, nach Konfidenzschwelle gefiltert,
         * pro Kategoriename dedupliziert - da nach dem Sortieren zuerst der jeweils HOECHSTE
         * Score einer Kategorie uebrig bleibt, ist das effektiv "maximaler Score je Kategorie
         * ueber den gesamten Clip". Unveraendert aus der Vor-Etappe-1.5-Implementierung von
         * `classifyDetailedScored` herausgeloest (keine Verhaltensaenderung).
         */
        private fun extrahiereTopKategorien(result: AudioClassifierResult, schwelle: Float): List<ScoredCategory> =
            result.classificationResults()
                .flatMap { it.classifications() }
                .flatMap { it.categories() }
                .filter { it.score() > schwelle }
                .sortedByDescending { it.score() }
                .map { ScoredCategory(it.categoryName(), it.score()) }
                .distinctBy { it.name }
    }
}
