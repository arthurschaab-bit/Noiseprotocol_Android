package com.example.lrmprotokoll.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val V9_DB_NAME = "v9-to-v10-migration-test"

/** Migration 9 -> 10: `diagnostic_log_entries` fuer M6 (Diagnose-Log, Plan Abschnitt 6). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseV10MigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    private fun erzeugeV9Datenbank() {
        helper.createDatabase(V9_DB_NAME, 9).use { db ->
            db.execSQL(
                "INSERT INTO noise_records (id, timestamp, amplitude, dbValue, filePath, label, detectedLabel, meterConnected) " +
                    "VALUES (1, 1700000000000, 0.42, 63.5, '/data/rec1.wav', 'Bohren', 'Bohren', 0)"
            )
            db.execSQL(
                "INSERT INTO sessions (id, startedAt, endedAt, deviceAddress, deviceName, weighting, timeWeighting) " +
                    "VALUES (1, 1700000000000, NULL, 'AA:BB:CC:DD:EE:FF', 'PCE-323', NULL, NULL)"
            )
        }
    }

    private fun oeffneUeberProduktionspfad(context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, V9_DB_NAME)
            .addMigrations(*ALLE_MIGRATIONEN)
            .allowMainThreadQueries()
            .build()

    @Test
    fun bestandAusV9UeberlebtDieMigrationAufV10() {
        erzeugeV9Datenbank()
        val database = oeffneUeberProduktionspfad(ApplicationProvider.getApplicationContext())

        database.query("SELECT dbValue FROM noise_records WHERE id = 1", null).use { cursor ->
            cursor.moveToFirst()
            assertEquals(63.5, cursor.getDouble(0), 0.0001)
        }
        database.query("SELECT deviceName FROM sessions WHERE id = 1", null).use { cursor ->
            cursor.moveToFirst()
            assertEquals("PCE-323", cursor.getString(0))
        }

        database.close()
    }

    @Test
    fun neueTabelleExistiertUndIstBenutzbar() {
        erzeugeV9Datenbank()
        val database = oeffneUeberProduktionspfad(ApplicationProvider.getApplicationContext())

        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO diagnostic_log_entries (timestamp, message) VALUES (1700000000000, 'DEGRADED: Datenstillstand')"
        )

        database.query("SELECT message FROM diagnostic_log_entries", null).use { cursor ->
            cursor.moveToFirst()
            assertEquals("DEGRADED: Datenstillstand", cursor.getString(0))
        }

        database.close()
    }

    @Test
    fun zweitesOeffnenNachDerMigrationLaeuftDurch() {
        erzeugeV9Datenbank()
        val context = ApplicationProvider.getApplicationContext<Context>()

        oeffneUeberProduktionspfad(context).apply {
            query("SELECT COUNT(*) FROM diagnostic_log_entries", null).use { it.moveToFirst() }
            close()
        }

        oeffneUeberProduktionspfad(context).apply {
            query("SELECT COUNT(*) FROM sessions", null).use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
            }
            close()
        }
    }
}
