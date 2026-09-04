package com.example.lrmprotokoll.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Ein Beweisvideo zu einem Messvorgang (M11 Etappe B, Owner-Auftrag "Videobeweis starten
 * waehrend Aufzeichnung").
 *
 * **Wozu:** Waehrend eine Messung laeuft, dokumentiert der Nutzer die Laermquelle selbst - was
 * dort geschieht, laesst sich mit Pegelwerten allein nicht zeigen. Wie bei
 * [DokumentationsFotoEntity] zaehlt die Zuordnung und der Zeitstempel mehr als die Bildqualitaet.
 *
 * [sessionId] ist non-null: Der Auftrag lautet "waehrend Aufzeichnung", es gibt also immer eine
 * laufende [SessionEntity] - seit M11/E1 auch beim reinen Mikrofonlauf.
 *
 * [hatTonspur] ist kein Beiwerk. Das Video wird nach Owner-Entscheidung E9 (V4) **ohne** Tonspur
 * aufgenommen und der Ton nachtraeglich aus dem laufenden `AudioRecord` einmultiplext. Laeuft
 * das Mikrofon nicht mit, bleibt das Video stumm - und dann muss aus dem Datensatz hervorgehen,
 * ob Stille "es war leise" oder "es wurde ohne Ton aufgezeichnet" bedeutet. Das ist der
 * Unterschied zwischen einem Beweis und einem Missverstaendnis.
 *
 * [tonGemuxt] trennt die stumme Zwischenfassung von der fertigen Datei: Zwischen dem Stopp der
 * Aufnahme und dem Ende des Mux-Laufs existiert die Datei bereits, ist aber weder das, was
 * abgespielt, noch das, was hochgeladen werden soll.
 */
@Entity(tableName = "beweisvideos")
data class BeweisVideoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val dateiPfad: String,
    val gestartetAm: Long,
    val dauerMs: Long,
    val hatTonspur: Boolean,
    val groesseBytes: Long,
    val notiz: String? = null,
    /**
     * Solange `false`, ist nur die stumme Fassung fertig. Ein Video ohne abgeschlossenen
     * Mux-Lauf wird NICHT hochgeladen - sonst landet die stumme Zwischenfassung in Drive.
     * Bei [hatTonspur] `false` gibt es nichts zu muxen; dort wird das Feld sofort `true`.
     */
    val tonGemuxt: Boolean = false,
    /**
     * Der Mux-Lauf ist endgueltig gescheitert. Ohne dieses Feld gab es keinen Endzustand: Die
     * Oberflaeche zeigte "Ton wird hinzugefuegt ..." unbegrenzt weiter, obwohl nichts mehr
     * passierte. Die stumme Datei bleibt erhalten und abspielbar - sie ist auch ohne Ton ein
     * Beleg.
     */
    val muxFehlgeschlagen: Boolean = false,
    /** Drive-Datei-ID, sobald hochgeladen - `null` heisst "noch nicht hochgeladen". */
    val driveFileId: String? = null,
    /**
     * Session-URI einer angefangenen resumable-Uebertragung. Wird gespeichert, BEVOR der erste
     * Datenblock rausgeht: Ein Prozess-Neustart mitten im Upload soll nicht wieder bei null
     * beginnen. Rund eine Woche gueltig.
     */
    val uploadSessionUri: String? = null,
    /** Vom Server bestaetigte Bytes einer unterbrochenen Uebertragung. */
    val hochgeladeneBytes: Long = 0,
)

@Dao
interface BeweisVideoDao {

    @Insert
    suspend fun insert(video: BeweisVideoEntity): Long

    @Query("SELECT * FROM beweisvideos WHERE sessionId = :sessionId ORDER BY gestartetAm")
    suspend fun fuerSession(sessionId: Long): List<BeweisVideoEntity>

    @Query("SELECT * FROM beweisvideos WHERE sessionId = :sessionId ORDER BY gestartetAm")
    fun fuerSessionFlow(sessionId: Long): Flow<List<BeweisVideoEntity>>

    @Query("SELECT * FROM beweisvideos WHERE id = :id")
    suspend fun byId(id: Long): BeweisVideoEntity?

    /** Fuer die Upload-Uebersicht: alle Videos, neueste zuerst. */
    @Query("SELECT * FROM beweisvideos ORDER BY gestartetAm DESC LIMIT :grenze")
    fun neuesteFlow(grenze: Int = 100): Flow<List<BeweisVideoEntity>>

    /**
     * Fuer den Drive-Sync: alles, was noch keine [BeweisVideoEntity.driveFileId] hat **und**
     * fertig gemuxt ist. Die zweite Bedingung ist der Grund, warum diese Abfrage nicht wie
     * [DokumentationsFotoDao.nichtHochgeladene] aussieht.
     */
    @Query("SELECT * FROM beweisvideos WHERE driveFileId IS NULL AND tonGemuxt = 1 ORDER BY gestartetAm")
    suspend fun nichtHochgeladene(): List<BeweisVideoEntity>

    /** Videos, deren Mux-Lauf noch aussteht - fuer die Wiederaufnahme nach Prozess-Tod. */
    @Query("SELECT * FROM beweisvideos WHERE tonGemuxt = 0 AND muxFehlgeschlagen = 0 ORDER BY gestartetAm")
    suspend fun ungemuxte(): List<BeweisVideoEntity>

    /** Endzustand nach einem gescheiterten Mux-Lauf - die stumme Datei bleibt, wie sie ist. */
    @Query("UPDATE beweisvideos SET muxFehlgeschlagen = 1 WHERE id = :id")
    suspend fun setzeMuxFehlgeschlagen(id: Long)

    @Query("UPDATE beweisvideos SET driveFileId = :fileId WHERE id = :id")
    suspend fun setzeDriveFileId(id: Long, fileId: String)

    @Query("UPDATE beweisvideos SET uploadSessionUri = :sessionUri, hochgeladeneBytes = :bytes WHERE id = :id")
    suspend fun setzeUploadFortschritt(id: Long, sessionUri: String?, bytes: Long)

    /** Nach dem Stopp der Kamera: erst dann stehen Dauer und Dateigroesse fest. */
    @Query("UPDATE beweisvideos SET dauerMs = :dauerMs, groesseBytes = :groesseBytes WHERE id = :id")
    suspend fun setzeAufnahmeergebnis(id: Long, dauerMs: Long, groesseBytes: Long)

    /** Nach erfolgreichem Mux-Lauf: neue Datei, neue Groesse, Ton drin. */
    @Query(
        "UPDATE beweisvideos SET dateiPfad = :dateiPfad, groesseBytes = :groesseBytes, " +
            "hatTonspur = :hatTonspur, tonGemuxt = 1, muxFehlgeschlagen = 0 WHERE id = :id"
    )
    suspend fun setzeGemuxt(id: Long, dateiPfad: String, groesseBytes: Long, hatTonspur: Boolean)

    @Query("DELETE FROM beweisvideos WHERE id = :id")
    suspend fun loesche(id: Long)
}
