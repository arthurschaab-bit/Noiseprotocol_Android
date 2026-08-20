package com.example.lrmprotokoll.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val V7_DB_NAME = "v7-to-v8-migration-test"

/**
 * Migration 7 -> 8: die Tabellen `level_samples` und `drive_daily_files` fuer M7b.
 *
 * Wie bei der vorherigen Migration ist die eigentliche Frage nicht "kommen die neuen Tabellen
 * an", sondern "bleibt der Bestand aus M5 (alerts) und davor unangetastet".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseV8MigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList<androidx.room.migration.AutoMigrationSpec>(),
        RobolectricOpenHelperFactory()
    )

    private fun erzeugeV7Datenbank() {
        helper.createDatabase(V7_DB_NAME, 7).use { db ->
            db.execSQL(
                "INSERT INTO noise_records (id, timestamp, amplitude, dbValue, filePath, label, detectedLabel) " +
                    "VALUES (1, 1700000000000, 0.42, 63.5, '/data/rec1.wav', 'Bohren', 'Bohren')"
            )
            db.execSQL(
                "INSERT INTO alerts (id, sessionId, outageSince, raisedAt, resolvedAt, reason, recipients, deliveryState, attempts, escalations) " +
                    "VALUES (1, NULL, 1700000000000, 1700000060000, NULL, 'DISCONNECTED', 'NTFY', 'SENT', 1, 0)"
            )
        }
    }

    private fun oeffneUeberProduktionspfad(context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, V7_DB_NAME)
            .addMigrations(*ALLE_MIGRATIONEN)
            .allowMainThreadQueries()
            .build()

    @Test
    fun bestandAusV7UeberlebtDieMigrationAufV8() {
        erzeugeV7Datenbank()
        val database = oeffneUeberProduktionspfad(ApplicationProvider.getApplicationContext())

        database.query("SELECT dbValue FROM noise_records WHERE id = 1", null).use { cursor ->
            cursor.moveToFirst()
            assertEquals(63.5, cursor.getDouble(0), 0.0001)
        }
        database.query("SELECT reason, deliveryState FROM alerts WHERE id = 1", null).use { cursor ->
            cursor.moveToFirst()
            assertEquals("DISCONNECTED", cursor.getString(0))
            assertEquals("SENT", cursor.getString(1))
        }

        database.close()
    }

    @Test
    fun neueTabellenExistierenUndSindBenutzbar() {
        erzeugeV7Datenbank()
        val database = oeffneUeberProduktionspfad(ApplicationProvider.getApplicationContext())

        database.query("SELECT COUNT(*) FROM level_samples", null).use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO level_samples (at, levelDb, source) VALUES (1700000000000, 55.5, 'MIKROFON')"
        )
        database.query("SELECT levelDb, source FROM level_samples", null).use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals(55.5, cursor.getDouble(0), 0.0001)
            assertEquals("MIKROFON", cursor.getString(1))
        }

        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO drive_daily_files (date, fileId, lastSyncedAt, lastRowCount, state) " +
                "VALUES ('2026-08-19', NULL, 1700000000000, 0, 'PENDING')"
        )
        database.query("SELECT fileId, state FROM drive_daily_files WHERE date = '2026-08-19'", null).use { cursor ->
            cursor.moveToFirst()
            assertNull(cursor.getString(0))
            assertEquals("PENDING", cursor.getString(1))
        }

        database.close()
    }

    @Test
    fun zweitesOeffnenNachDerMigrationLaeuftDurch() {
        erzeugeV7Datenbank()
        val context = ApplicationProvider.getApplicationContext<Context>()

        oeffneUeberProduktionspfad(context).apply {
            query("SELECT COUNT(*) FROM level_samples", null).use { it.moveToFirst() }
            close()
        }

        oeffneUeberProduktionspfad(context).apply {
            query("SELECT COUNT(*) FROM noise_records", null).use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
            }
            close()
        }
    }
}
