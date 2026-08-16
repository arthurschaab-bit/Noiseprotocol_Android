package com.example.lrmprotokoll.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val V4_DB_NAME = "v4-migration-test"

/**
 * Prueft den Migrationspfad 4 -> 6 mit dem Schema, das tatsaechlich auf einem Geraet vorgefunden
 * wurde.
 *
 * Warum dieser Test zusaetzlich zu [AppDatabaseMigrationTest] noetig ist: Jener Test baut seine
 * Ausgangsdatenbank ueber MigrationTestHelper aus dem exportierten Schema-JSON auf. Sie passt damit
 * per Konstruktion zur aktuellen Deklaration - eine reale, aeltere Geraetedatenbank kann er
 * konstruktionsbedingt nicht nachbilden. Genau daran ist der Fehler vorbeigelaufen: Auf dem
 * Testgeraet lag Version 4, und die App stuerzte beim Start mit
 * "A migration from 4 to 6 was required but not found" ab.
 *
 * Dieser Test legt die v4-Datenbank deshalb per rohem SQL an - Wort fuer Wort so, wie sie vom Geraet
 * ausgelesen wurde. Ein 4.json als Baseline existiert nicht und laesst sich auch nicht
 * nachtraeglich erzeugen, weil die damalige Room-Version kein Schema exportiert hat.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseV4MigrationTest {

    /** Exakt der vom Geraet ausgelesene Stand (PRAGMA user_version = 4). */
    private fun erzeugeV4Datenbank(context: Context) {
        val datei = context.getDatabasePath(V4_DB_NAME)
        datei.parentFile?.mkdirs()
        if (datei.exists()) datei.delete()

        val db = SQLiteDatabase.openOrCreateDatabase(datei, null)
        db.execSQL(
            "CREATE TABLE `noise_records` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`timestamp` INTEGER NOT NULL, " +
                "`amplitude` REAL NOT NULL, " +
                "`filePath` TEXT NOT NULL, " +
                "`label` TEXT, " +
                "`detectedLabel` TEXT)"
        )
        // Room legt diese Tabelle selbst an; auf dem Geraet ist sie vorhanden, deshalb hier
        // ebenfalls. Der Identity-Hash ist beliebig: Auf dem Upgrade-Pfad vergleicht Room ihn nicht,
        // sondern schreibt ihn nach erfolgreicher Migration neu.
        db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
        db.execSQL("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, 'alter-hash-aus-v4')")

        db.execSQL(
            "INSERT INTO noise_records (id, timestamp, amplitude, filePath, label, detectedLabel) " +
                "VALUES (1, 1700000000000, 0.42, '/data/rec1.wav', 'Bohren', 'Bohren')"
        )
        db.execSQL(
            "INSERT INTO noise_records (id, timestamp, amplitude, filePath, label, detectedLabel) " +
                "VALUES (2, 1700000005000, 0.11, '/data/rec2.wav', NULL, NULL)"
        )

        db.version = 4
        db.close()
    }

    @Test
    fun v4BestandUeberlebtMigrationAufV6() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        erzeugeV4Datenbank(context)

        // Oeffnen ueber denselben Builder-Pfad wie in AppDatabase.getDatabase(): mit der Migration,
        // ohne destruktiven Fallback. Schlaegt die Migration fehl oder passt das Ergebnis nicht zum
        // deklarierten v6-Schema, wirft Room hier.
        val database = Room.databaseBuilder(context, AppDatabase::class.java, V4_DB_NAME)
            .addMigrations(MIGRATION_4_6)
            .allowMainThreadQueries()
            .build()

        // 1. Die Altdaten sind vollstaendig und inhaltlich unveraendert.
        database.query(
            "SELECT id, timestamp, amplitude, dbValue, filePath, label, detectedLabel " +
                "FROM noise_records ORDER BY id",
            null
        ).use { cursor ->
            assertEquals(2, cursor.count)

            cursor.moveToPosition(0)
            assertEquals(1L, cursor.getLong(0))
            assertEquals(1700000000000L, cursor.getLong(1))
            assertEquals(0.42, cursor.getDouble(2), 0.0001)
            assertEquals("/data/rec1.wav", cursor.getString(4))
            assertEquals("Bohren", cursor.getString(5))
            assertEquals("Bohren", cursor.getString(6))

            cursor.moveToPosition(1)
            assertEquals(2L, cursor.getLong(0))
            assertEquals("/data/rec2.wav", cursor.getString(4))
            assertNull(cursor.getString(5))
            assertNull(cursor.getString(6))
        }

        // 2. Die neue Spalte existiert und ist mit dem Default belegt - v4 kannte keinen Pegelwert.
        database.query("SELECT dbValue FROM noise_records ORDER BY id", null).use { cursor ->
            cursor.moveToPosition(0)
            assertEquals(0.0, cursor.getDouble(0), 0.0001)
        }

        // 3. Die in v4 fehlende Tabelle existiert und ist benutzbar.
        database.query("SELECT COUNT(*) FROM reference_sounds", null).use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO reference_sounds (name, pattern) VALUES ('Presslufthammer', 'Jackhammer,Hammer')"
        )
        database.query("SELECT name FROM reference_sounds", null).use { cursor ->
            cursor.moveToFirst()
            assertEquals("Presslufthammer", cursor.getString(0))
        }

        database.close()
    }

    /**
     * Ein zweiter Start darf nicht erneut migrieren wollen. Sichert ab, dass Room die
     * Versionsnummer und den Identity-Hash nach der Migration korrekt fortgeschrieben hat.
     */
    @Test
    fun zweitesOeffnenNachMigrationLaeuftDurch() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        erzeugeV4Datenbank(context)

        Room.databaseBuilder(context, AppDatabase::class.java, V4_DB_NAME)
            .addMigrations(MIGRATION_4_6)
            .allowMainThreadQueries()
            .build()
            .apply {
                query("SELECT COUNT(*) FROM noise_records", null).use { it.moveToFirst() }
                close()
            }

        Room.databaseBuilder(context, AppDatabase::class.java, V4_DB_NAME)
            .addMigrations(MIGRATION_4_6)
            .allowMainThreadQueries()
            .build()
            .apply {
                query("SELECT COUNT(*) FROM noise_records", null).use { cursor ->
                    cursor.moveToFirst()
                    assertEquals(2, cursor.getInt(0))
                }
                close()
            }
    }
}
