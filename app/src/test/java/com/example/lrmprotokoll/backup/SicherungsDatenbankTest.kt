package com.example.lrmprotokoll.backup

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Voll- und Teilsicherung (docs/PROMPT_SICHERUNG_VOLL_UND_TEIL.md): Vollsicherung ohne
 * `level_samples` plus kumulative Teilsicherung muss nach dem Einspielen denselben Stand ergeben
 * wie die laufende Datenbank - bis auf die bewusst nicht gesicherten Rohwerte.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SicherungsDatenbankTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun oeffne(datei: File) =
        SQLiteDatabase.openDatabase(datei.absolutePath, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY)

    private fun baueLiveDatenbank(): File {
        val datei = File(tempFolder.root, "live.db")
        oeffne(datei).use { db ->
            db.execSQL("CREATE TABLE sessions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, endedAt INTEGER)")
            db.execSQL(
                "CREATE TABLE measurements (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "sessionId INTEGER NOT NULL, levelDb REAL NOT NULL)",
            )
            db.execSQL("CREATE TABLE level_samples (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, at INTEGER NOT NULL)")
            db.execSQL(
                "CREATE TABLE klassifikations_rohdaten (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "recordId INTEGER NOT NULL, topKlassen TEXT NOT NULL)",
            )
            db.execSQL("INSERT INTO sessions (endedAt) VALUES (NULL)")
            repeat(3) { db.execSQL("INSERT INTO measurements (sessionId, levelDb) VALUES (1, 50.0)") }
            repeat(5) { db.execSQL("INSERT INTO level_samples (at) VALUES ($it)") }
            db.execSQL("INSERT INTO klassifikations_rohdaten (recordId, topKlassen) VALUES (7, 'alt')")
        }
        return datei
    }

    private fun zeilen(
        datei: File,
        sql: String,
    ): List<String> =
        oeffne(datei).use { db ->
            db.rawQuery(sql, null).use { c ->
                buildList {
                    while (c.moveToNext()) add((0 until c.columnCount).joinToString("|") { c.getString(it) ?: "null" })
                }
            }
        }

    @Test
    fun vollPlusTeilErgibtDenAktuellenStandOhneRohwerte() {
        val live = baueLiveDatenbank()

        // Vollsicherung: Kopie ohne Rohwerte, Stand = hoechste IDs zu diesem Zeitpunkt.
        val voll = File(tempFolder.root, "voll.db").also { live.copyTo(it) }
        val stand = SicherungsDatenbank.entferneRohwerteUndErmittleStand(voll)
        assertEquals(SicherungsDatenbank.Stand(basisMesswertId = 3, basisRohdatenId = 1), stand)
        assertEquals(listOf("0"), zeilen(voll, "SELECT COUNT(*) FROM level_samples"))
        assertEquals(listOf("5"), zeilen(live, "SELECT COUNT(*) FROM level_samples"))

        // Danach im laufenden Betrieb: neue Messwerte, Session beendet, Aufnahme 7 neu klassifiziert.
        oeffne(live).use { db ->
            repeat(2) { db.execSQL("INSERT INTO measurements (sessionId, levelDb) VALUES (1, 61.5)") }
            db.execSQL("UPDATE sessions SET endedAt = 999 WHERE id = 1")
            db.execSQL("DELETE FROM klassifikations_rohdaten WHERE recordId = 7")
            db.execSQL("INSERT INTO klassifikations_rohdaten (recordId, topKlassen) VALUES (7, 'neu')")
        }

        val teil = File(tempFolder.root, "teil.db")
        SicherungsDatenbank.baueTeilsicherung(live, teil, stand)
        assertEquals("Nur der Zuwachs an Messwerten", listOf("2"), zeilen(teil, "SELECT COUNT(*) FROM measurements"))

        // Wiederherstellen: Vollsicherung + Teilsicherung.
        SicherungsDatenbank.spieleTeilsicherungEin(voll, teil)

        for (sql in listOf(
            "SELECT * FROM sessions ORDER BY id",
            "SELECT * FROM measurements ORDER BY id",
            "SELECT recordId, topKlassen FROM klassifikations_rohdaten ORDER BY recordId",
        )) {
            assertEquals(sql, zeilen(live, sql), zeilen(voll, sql))
        }
        assertEquals(listOf("0"), zeilen(voll, "SELECT COUNT(*) FROM level_samples"))
    }

    @Test
    fun teilsicherungZweimalEinspielenVerdoppeltNichts() {
        val live = baueLiveDatenbank()
        val voll = File(tempFolder.root, "voll.db").also { live.copyTo(it) }
        val stand = SicherungsDatenbank.entferneRohwerteUndErmittleStand(voll)
        oeffne(live).use { it.execSQL("INSERT INTO measurements (sessionId, levelDb) VALUES (1, 70.0)") }
        val teil = File(tempFolder.root, "teil.db")
        SicherungsDatenbank.baueTeilsicherung(live, teil, stand)

        SicherungsDatenbank.spieleTeilsicherungEin(voll, teil)
        SicherungsDatenbank.spieleTeilsicherungEin(voll, teil)

        assertEquals(listOf("4"), zeilen(voll, "SELECT COUNT(*) FROM measurements"))
    }

    /** Schema wie in Room: Rohdaten haengen per Fremdschluessel mit ON DELETE CASCADE an der Aufnahme. */
    private fun baueLiveMitFremdschluessel(): File {
        val datei = File(tempFolder.root, "live_fk.db")
        oeffne(datei).use { db ->
            db.enableWriteAheadLogging()
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("CREATE TABLE noise_records (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, label TEXT)")
            db.execSQL(
                "CREATE TABLE klassifikations_rohdaten (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "recordId INTEGER NOT NULL, topKlassen TEXT NOT NULL, " +
                    "FOREIGN KEY(recordId) REFERENCES noise_records(id) ON DELETE CASCADE)",
            )
            db.execSQL("INSERT INTO noise_records (id, label) VALUES (7, 'a')")
            db.execSQL("INSERT INTO klassifikations_rohdaten (recordId, topKlassen) VALUES (7, 'x')")
        }
        return datei
    }

    /**
     * Review zu #274 (P1): Die Teilsicherung liest alle Tabellen in einer aufgeschobenen
     * Transaktion, also aus einem WAL-Schnappschuss. Gleichzeitiges Schreiben laesst sich hier
     * nicht pruefen - Robolectrics SQLite blockiert schon bei einem einzelnen WAL-Leser jeden
     * Schreiber (auf Geraeten nicht). Geprueft wird deshalb die zweite Sicherung dahinter: Kommt
     * dennoch eine Teilsicherung mit Rohdaten ohne ihre Aufnahme an, entfernt das Einspielen sie,
     * statt einen widerspruechlichen Stand als erfolgreich zu melden.
     */
    @Test
    fun widerspruechlicheTeilsicherungHinterlaesstKeineVerwaistenRohdaten() {
        val live = baueLiveMitFremdschluessel()
        val voll = File(tempFolder.root, "voll_fk.db").also { live.copyTo(it) }
        val stand = SicherungsDatenbank.entferneRohwerteUndErmittleStand(voll)
        val teil = File(tempFolder.root, "teil_fk.db")
        SicherungsDatenbank.baueTeilsicherung(live, teil, stand)
        // So saehe ein Export aus, der Aufnahme 8 nicht, ihre Rohdaten aber schon gesehen hat.
        oeffne(teil).use { it.execSQL("INSERT INTO klassifikations_rohdaten (id, recordId, topKlassen) VALUES (99, 8, 'neu')") }

        SicherungsDatenbank.spieleTeilsicherungEin(voll, teil)

        assertEquals(listOf("0"), zeilen(voll, "SELECT COUNT(*) FROM klassifikations_rohdaten WHERE recordId = 8"))
        assertEquals(emptyList<String>(), zeilen(voll, "PRAGMA foreign_key_check"))
    }

    /**
     * Review zu #274 (P2): Eine vollgesicherte Aufnahme wird danach endgueltig geloescht. Nach
     * Voll- plus Teilsicherung duerfen ihre Rohdaten aus der Vollsicherung nicht verwaist
     * zurueckbleiben.
     */
    @Test
    fun geloeschteVollgesicherteAufnahmeHinterlaesstKeineVerwaistenRohdaten() {
        val live = baueLiveMitFremdschluessel()
        val voll = File(tempFolder.root, "voll_fk.db").also { live.copyTo(it) }
        val stand = SicherungsDatenbank.entferneRohwerteUndErmittleStand(voll)
        oeffne(live).use { db ->
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("DELETE FROM noise_records WHERE id = 7")
        }
        val teil = File(tempFolder.root, "teil_fk.db")
        SicherungsDatenbank.baueTeilsicherung(live, teil, stand)

        SicherungsDatenbank.spieleTeilsicherungEin(voll, teil)

        assertEquals(listOf("0"), zeilen(voll, "SELECT COUNT(*) FROM klassifikations_rohdaten"))
        assertEquals(emptyList<String>(), zeilen(voll, "PRAGMA foreign_key_check"))
    }
}
