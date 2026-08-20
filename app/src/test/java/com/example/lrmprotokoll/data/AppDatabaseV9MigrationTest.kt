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

private const val V8_DB_NAME = "v8-to-v9-migration-test"

/**
 * Migration 8 -> 9: die Messreihe fuer M4 (sessions/measurements/connection_events/
 * minute_aggregates) und drei neue Spalten auf noise_records (Trigger-Umstellung, Plan 4.5).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseV9MigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    private fun erzeugeV8Datenbank() {
        helper.createDatabase(V8_DB_NAME, 8).use { db ->
            db.execSQL(
                "INSERT INTO noise_records (id, timestamp, amplitude, dbValue, filePath, label, detectedLabel) " +
                    "VALUES (1, 1700000000000, 0.42, 63.5, '/data/rec1.wav', 'Bohren', 'Bohren')"
            )
            db.execSQL(
                "INSERT INTO alerts (id, sessionId, outageSince, raisedAt, resolvedAt, reason, recipients, deliveryState, attempts, escalations) " +
                    "VALUES (1, NULL, 1700000000000, 1700000060000, NULL, 'DISCONNECTED', 'NTFY', 'SENT', 1, 0)"
            )
            db.execSQL(
                "INSERT INTO level_samples (at, levelDb, source) VALUES (1700000000000, 55.5, 'MIKROFON')"
            )
        }
    }

    private fun oeffneUeberProduktionspfad(context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, V8_DB_NAME)
            .addMigrations(*ALLE_MIGRATIONEN)
            .allowMainThreadQueries()
            .build()

    @Test
    fun bestandAusV8UeberlebtDieMigrationAufV9() {
        erzeugeV8Datenbank()
        val database = oeffneUeberProduktionspfad(ApplicationProvider.getApplicationContext())

        database.query("SELECT dbValue, calibratedDbA, meterWeighting, meterConnected FROM noise_records WHERE id = 1", null).use { cursor ->
            cursor.moveToFirst()
            assertEquals(63.5, cursor.getDouble(0), 0.0001)
            assertNull(cursor.getString(1))
            assertNull(cursor.getString(2))
            assertEquals(0, cursor.getInt(3)) // Default false fuer Altbestand
        }
        database.query("SELECT reason FROM alerts WHERE id = 1", null).use { cursor ->
            cursor.moveToFirst()
            assertEquals("DISCONNECTED", cursor.getString(0))
        }
        database.query("SELECT levelDb FROM level_samples", null).use { cursor ->
            cursor.moveToFirst()
            assertEquals(55.5, cursor.getDouble(0), 0.0001)
        }

        database.close()
    }

    @Test
    fun neueTabellenExistierenUndSindBenutzbar() {
        erzeugeV8Datenbank()
        val database = oeffneUeberProduktionspfad(ApplicationProvider.getApplicationContext())

        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO sessions (id, startedAt, endedAt, deviceAddress, deviceName, weighting, timeWeighting) " +
                "VALUES (1, 1700000000000, NULL, 'AA:BB:CC:DD:EE:FF', 'PCE-323', NULL, NULL)"
        )
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO measurements (sessionId, timestamp, levelDb, weighting, flags) " +
                "VALUES (1, 1700000000000, 55.5, NULL, 0)"
        )
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO connection_events (sessionId, at, type, reason) " +
                "VALUES (1, 1700000000000, 'CONNECTED', NULL)"
        )
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO minute_aggregates (sessionId, minuteStart, leqDb, maxDb, minDb, sampleCount, weighting) " +
                "VALUES (1, 1700000000000, 55.5, 60.0, 50.0, 120, NULL)"
        )

        database.query("SELECT deviceName FROM sessions WHERE id = 1", null).use { cursor ->
            cursor.moveToFirst()
            assertEquals("PCE-323", cursor.getString(0))
        }
        database.query("SELECT COUNT(*) FROM measurements", null).use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        database.query("SELECT type FROM connection_events", null).use { cursor ->
            cursor.moveToFirst()
            assertEquals("CONNECTED", cursor.getString(0))
        }
        database.query("SELECT sampleCount FROM minute_aggregates", null).use { cursor ->
            cursor.moveToFirst()
            assertEquals(120, cursor.getInt(0))
        }

        database.close()
    }

    @Test
    fun zweitesOeffnenNachDerMigrationLaeuftDurch() {
        erzeugeV8Datenbank()
        val context = ApplicationProvider.getApplicationContext<Context>()

        oeffneUeberProduktionspfad(context).apply {
            query("SELECT COUNT(*) FROM sessions", null).use { it.moveToFirst() }
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
