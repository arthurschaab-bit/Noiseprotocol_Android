package com.example.lrmprotokoll.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

/**
 * KI-Umbau Etappe 1 (Beweisdokumentation Baulärm), Kernaufgabe 1.4: die rohen, unaggregierten
 * YAMNet-Frame-Scores einer klassifizierten Aufnahme - damit jede spätere Änderung an Schwellen,
 * Gruppierung oder Zeitaggregation (Etappe 2/3) ohne erneute Inferenz über die WAV-Datei
 * auskommt. Ohne diese Tabelle wäre jede Nachjustierung eine komplette Neu-Inferenz über den
 * gesamten Aufnahmebestand.
 *
 * [recordId] referenziert [NoiseRecord.id] mit `CASCADE`-Löschung: eine Aufnahme und ihre
 * Rohdaten sind eine untrennbare Einheit - Löschen des Beweismittels (Papierkorb-Bereinigung)
 * darf keine verwaisten Rohdatensätze hinterlassen.
 *
 * [modellVersion] hält Dateiname und Hash der eingebetteten `yamnet.tflite` fest, damit spätere
 * Auswertungen erkennen können, ob Rohdaten aus einer älteren Modellversion stammen und damit
 * ggf. nicht direkt vergleichbar sind.
 *
 * [klassenIndizes] und [frameScores] speichern bewusst NICHT alle 521 YAMNet-Klassen pro Frame -
 * das wäre für eine 60-Sekunden-Aufnahme unnötig groß. Stattdessen eine feste, für Baulärm
 * relevante Untermenge (siehe [ROHDATEN_KLASSEN_INDIZES]), pro Frame und Klasse ein quantisiertes
 * Byte (`round(score * 255)`), frame-major abgelegt:
 * `frameScores[frame * klassenIndizes.size + klasse]`.
 *
 * [topKlassen] ist bewusst NICHT hart auf "Top-15" begrenzt, obwohl sie primär der Anzeige
 * dient: der bestehende Referenzmuster-Abgleich in `NoiseClassifier.classify()` vergleicht
 * Mengen aus ALLEN Kategorien über der Konfidenzschwelle (`classifyDetailed()`, unbegrenzt) -
 * eine harte 15er-Kappung hier würde bei manchen gelernten Referenzen die
 * "Gelernt: …"-Erkennung real verschlechtern. [topKlassen] enthält deshalb alle Kategorien über
 * der zum Klassifizierungszeitpunkt geltenden Konfidenzschwelle (JSON-Array aus Name+Score),
 * damit [leiteLabelAb][com.example.lrmprotokoll.audio.leiteLabelAb] das bisherige Verhalten
 * exakt reproduzieren kann (Etappe 1.5: "funktional unverändert").
 *
 * KI-Umbau Etappe 3.4: [impulsCrest]/[impulsKurtosis]/[impulsWiederholrateHz]/
 * [impulsPeakSchaerfe]/[impulsMittlererPegel] halten die physikalischen, YAMNet-UNABHÄNGIGEN
 * Merkmale der Hüllkurve fest (siehe [com.example.lrmprotokoll.audio.ImpulsMerkmale]) - die
 * "zweite Meinung" für die Fusion (Etappe 3.5). Alle fünf `null` bei Altaufnahmen (vor Etappe 3
 * klassifiziert) - wie bei den Etappe-1-Feldern kein Absturz, keine geratenen Werte.
 */
@Entity(
    tableName = "klassifikations_rohdaten",
    foreignKeys = [
        ForeignKey(
            entity = NoiseRecord::class,
            parentColumns = ["id"],
            childColumns = ["recordId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("recordId")],
)
data class KlassifikationsRohdaten(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordId: Long,
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
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KlassifikationsRohdaten) return false
        return id == other.id && recordId == other.recordId && modellVersion == other.modellVersion &&
            klassifiziertAm == other.klassifiziertAm && frameAnzahl == other.frameAnzahl &&
            frameDauerMs == other.frameDauerMs && frameHopMs == other.frameHopMs &&
            klassenIndizes.contentEquals(other.klassenIndizes) && frameScores.contentEquals(other.frameScores) &&
            topKlassen == other.topKlassen && impulsCrest == other.impulsCrest &&
            impulsKurtosis == other.impulsKurtosis && impulsWiederholrateHz == other.impulsWiederholrateHz &&
            impulsPeakSchaerfe == other.impulsPeakSchaerfe && impulsMittlererPegel == other.impulsMittlererPegel
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + recordId.hashCode()
        result = 31 * result + modellVersion.hashCode()
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

/** Room hat keine eingebaute Unterstützung für `IntArray` (anders als `ByteArray`/BLOB). */
class RohdatenConverters {
    @TypeConverter
    fun ausIntArray(werte: IntArray): String = werte.joinToString(",")

    @TypeConverter
    fun zuIntArray(csv: String): IntArray =
        if (csv.isBlank()) IntArray(0) else csv.split(",").map { it.toInt() }.toIntArray()
}
