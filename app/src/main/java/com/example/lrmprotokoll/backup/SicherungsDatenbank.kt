package com.example.lrmprotokoll.backup

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Datenbank-Handgriffe für Voll- und Teilsicherung (Owner-Entscheidung 10.10.2026,
 * docs/PROMPT_SICHERUNG_VOLL_UND_TEIL.md). Arbeitet ausschließlich auf eigenen Verbindungen zu
 * Dateien - nie auf der laufenden Room-Verbindung: Android verbietet `ATTACH` auf einer
 * Verbindung im WAL-Modus, und beim Einspielen ist Room ohnehin geschlossen.
 *
 * Aufteilung der Tabellen:
 * - [ROHWERT_TABELLE] (`level_samples`) wird nie gesichert: Puffer für den Drive-Sync, verdichtet
 *   in den Tages-CSVs, ~75 % der Datei.
 * - [ZUWACHS_TABELLEN] wachsen nur und sind groß: die Teilsicherung enthält nur Zeilen mit einer
 *   `id` über dem Stand der Vollsicherung.
 * - Alle übrigen Tabellen sind klein und werden in jeder Teilsicherung vollständig mitgenommen,
 *   weil sich dort auch bestehende Zeilen ändern (Labels, Session-Ende, Papierkorb).
 */
object SicherungsDatenbank {
    const val ROHWERT_TABELLE = "level_samples"
    private const val MESSWERTE = "measurements"
    private const val ROHDATEN = "klassifikations_rohdaten"
    private val ZUWACHS_TABELLEN = setOf(MESSWERTE, ROHDATEN)

    /** Interne Tabellen von SQLite/Android/Room - gehören nie in eine Teilsicherung. */
    private val INTERNE_TABELLEN = setOf("android_metadata", "room_master_table", "sqlite_sequence")

    /** Stand einer Vollsicherung: ab welchen IDs eine Teilsicherung Zuwachs mitnehmen muss. */
    data class Stand(
        val basisMesswertId: Long,
        val basisRohdatenId: Long,
    )

    /**
     * Leert [ROHWERT_TABELLE] in [kopie] (einer KOPIE der Datenbankdatei, nie der laufenden) und
     * gibt den Platz per `VACUUM` frei. Liefert den Stand für spätere Teilsicherungen.
     */
    fun entferneRohwerteUndErmittleStand(kopie: File): Stand =
        oeffne(kopie).use { db ->
            if (tabellen(db).contains(ROHWERT_TABELLE)) db.execSQL("DELETE FROM `$ROHWERT_TABELLE`")
            db.execSQL("VACUUM")
            Stand(maxId(db, MESSWERTE), maxId(db, ROHDATEN))
        }

    /**
     * Baut die Teilsicherung als eigene SQLite-Datei [ziel] aus der laufenden Datenbank
     * [quelle]: kleine Tabellen vollständig, [ZUWACHS_TABELLEN] nur über [stand] hinaus.
     * Die Tabellen in [ziel] tragen nur Spalten, keine Schlüssel oder Indizes - sie werden
     * ausschließlich von [spieleTeilsicherungEin] gelesen.
     */
    fun baueTeilsicherung(
        quelle: File,
        ziel: File,
        stand: Stand,
    ) {
        ziel.delete()
        oeffne(ziel).use { db ->
            db.execSQL("ATTACH DATABASE ? AS live", arrayOf(quelle.absolutePath))
            try {
                for (tabelle in tabellen(db, "live")) {
                    if (tabelle == ROHWERT_TABELLE || tabelle in INTERNE_TABELLEN) continue
                    val bedingung =
                        when (tabelle) {
                            MESSWERTE -> " WHERE id > ${stand.basisMesswertId}"
                            ROHDATEN -> " WHERE id > ${stand.basisRohdatenId}"
                            else -> ""
                        }
                    db.execSQL("CREATE TABLE main.`$tabelle` AS SELECT * FROM live.`$tabelle`$bedingung")
                }
            } finally {
                db.execSQL("DETACH DATABASE live")
            }
        }
    }

    /**
     * Spielt [teil] in die (bereits aus der Vollsicherung wiederhergestellte, nicht geöffnete)
     * Datenbankdatei [ziel] ein - alles in einer Transaktion: scheitert ein Schritt, bleibt der
     * Stand der Vollsicherung unverändert.
     *
     * - Kleine Tabellen: Inhalt komplett durch den der Teilsicherung ersetzt.
     * - `measurements`: Zuwachs angehängt.
     * - `klassifikations_rohdaten`: für jede Aufnahme der Teilsicherung ersetzt, weil eine
     *   erneute Klassifizierung den alten Datensatz löscht und einen neuen anlegt.
     *
     * Tabellen, die es in [ziel] nicht gibt, werden übergangen.
     */
    fun spieleTeilsicherungEin(
        ziel: File,
        teil: File,
    ) {
        oeffne(ziel).use { db ->
            db.execSQL("PRAGMA foreign_keys = OFF")
            db.execSQL("ATTACH DATABASE ? AS teil", arrayOf(teil.absolutePath))
            try {
                val vorhanden = tabellen(db)
                db.beginTransaction()
                try {
                    for (tabelle in tabellen(db, "teil")) {
                        if (tabelle !in vorhanden || tabelle == ROHWERT_TABELLE || tabelle in INTERNE_TABELLEN) continue
                        when (tabelle) {
                            MESSWERTE ->
                                db.execSQL("INSERT OR IGNORE INTO main.`$tabelle` SELECT * FROM teil.`$tabelle`")
                            ROHDATEN -> {
                                db.execSQL(
                                    "DELETE FROM main.`$tabelle` WHERE recordId IN (SELECT recordId FROM teil.`$tabelle`)",
                                )
                                db.execSQL("INSERT OR REPLACE INTO main.`$tabelle` SELECT * FROM teil.`$tabelle`")
                            }
                            else -> {
                                db.execSQL("DELETE FROM main.`$tabelle`")
                                db.execSQL("INSERT INTO main.`$tabelle` SELECT * FROM teil.`$tabelle`")
                            }
                        }
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            } finally {
                db.execSQL("DETACH DATABASE teil")
            }
        }
    }

    private fun oeffne(datei: File): SQLiteDatabase =
        SQLiteDatabase.openDatabase(
            datei.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY,
        )

    private fun tabellen(
        db: SQLiteDatabase,
        schema: String = "main",
    ): List<String> =
        db.rawQuery("SELECT name FROM $schema.sqlite_master WHERE type = 'table'", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    private fun maxId(
        db: SQLiteDatabase,
        tabelle: String,
    ): Long {
        if (tabelle !in tabellen(db)) return 0L
        return db.rawQuery("SELECT IFNULL(MAX(id), 0) FROM `$tabelle`", null).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }
    }
}
