package com.example.lrmprotokoll.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Kategorien der Fotodokumentation (M11 Etappe A).
 *
 * Als String in [DokumentationsFotoEntity.kategorie] gespeichert, nicht als Room-TypeConverter -
 * dieselbe Konvention wie [ConnectionEventType]. Ein unbekannter Wert aus der Datenbank faellt
 * ueber [vonName] auf [SONSTIGES] zurueck, statt zu werfen: Ein Foto mit unerwarteter Kategorie
 * ist immer noch ein Beleg.
 */
enum class FotoKategorie(val anzeigename: String) {
    MESSAUFBAU("Messaufbau"),
    KALIBRIERUNG("Kalibrierung"),
    SONSTIGES("Sonstiges");

    companion object {
        fun vonName(name: String?): FotoKategorie =
            entries.firstOrNull { it.name == name } ?: SONSTIGES
    }
}

/**
 * Ein Belegfoto zu einem Messvorgang (M11 Etappe A, Owner-Auftrag "Foto-Doku für Bericht beim
 * Starten eines Messvorgangs").
 *
 * **Wozu:** Ein Lärmprotokoll ist ein Beweismittel. Die haeufigste Entkraeftung eines privaten
 * Messprotokolls ist nicht "die Zahlen stimmen nicht", sondern "wir wissen nicht, wie und wo
 * gemessen wurde". Ein Foto vom aufgestellten Messgeraet beantwortet genau das - deshalb sind
 * Zeitstempel, Zuordnung und Unveraenderbarkeit wichtiger als Bildqualitaet.
 *
 * [sessionId] ist non-null: Seit M11/E1 eroeffnet auch ein reiner Mikrofonlauf eine
 * [SessionEntity] (siehe [com.example.lrmprotokoll.messreihe.MeasurementRecorder.starteMikrofonMessung]),
 * jeder Messvorgang hat also genau einen Anker. Ein zweiter, konkurrierender Anker haette jede
 * spaetere Auswertung doppelt behandeln muessen.
 *
 * [pruefsumme] ist der SHA-256 der Bilddatei zum Aufnahmezeitpunkt - der einzige Weg, spaeter zu
 * zeigen, dass ein Foto seit der Aufnahme nicht ausgetauscht wurde. Fuer ein Beweismittel ist
 * das der eigentliche Punkt.
 */
@Entity(tableName = "dokumentationsfotos")
data class DokumentationsFotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val kategorie: String,
    val dateiPfad: String,
    val aufgenommenAm: Long,
    val notiz: String? = null,
    /** Drive-Datei-ID, sobald hochgeladen - `null` heisst "noch nicht hochgeladen". */
    val driveFileId: String? = null,
    /** SHA-256 der Bilddatei, hex, kleingeschrieben. */
    val pruefsumme: String? = null,
)

@Dao
interface DokumentationsFotoDao {

    @Insert
    suspend fun insert(foto: DokumentationsFotoEntity): Long

    @Query("SELECT * FROM dokumentationsfotos WHERE sessionId = :sessionId ORDER BY aufgenommenAm")
    suspend fun fuerSession(sessionId: Long): List<DokumentationsFotoEntity>

    @Query("SELECT * FROM dokumentationsfotos WHERE sessionId = :sessionId ORDER BY aufgenommenAm")
    fun fuerSessionFlow(sessionId: Long): Flow<List<DokumentationsFotoEntity>>

    @Query("SELECT COUNT(*) FROM dokumentationsfotos WHERE sessionId = :sessionId AND kategorie = :kategorie")
    suspend fun anzahlFuerKategorie(sessionId: Long, kategorie: String): Int

    /** Fuer den Drive-Sync: alles, was noch keine [DokumentationsFotoEntity.driveFileId] hat. */
    @Query("SELECT * FROM dokumentationsfotos WHERE driveFileId IS NULL ORDER BY aufgenommenAm")
    suspend fun nichtHochgeladene(): List<DokumentationsFotoEntity>

    @Query("UPDATE dokumentationsfotos SET driveFileId = :fileId WHERE id = :id")
    suspend fun setzeDriveFileId(id: Long, fileId: String)

    @Query("SELECT * FROM dokumentationsfotos WHERE id = :id")
    suspend fun byId(id: Long): DokumentationsFotoEntity?

    @Query("DELETE FROM dokumentationsfotos WHERE id = :id")
    suspend fun loesche(id: Long)
}
