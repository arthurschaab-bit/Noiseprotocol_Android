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

private const val V10_DB_NAME = "v10-to-v11-migration-test"

/**
 * Migration 10 -> 11: `range` auf `sessions`, `timeWeighting`/`range` auf `measurements` und
 * `minute_aggregates` - vervollstaendigt die Geraeteeinstellungen im CSV-Export (Owner-Wunsch).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseV11MigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList<androidx.room.migration.AutoMigrationSpec>(),
        RobolectricOpenHelperFactory()
    )

    private fun erzeugeV10Datenbank() {
        helper.createDatabase(V10_DB_NAME, 10).use { db ->
            db.execSQL(
                "INSERT INTO sessions (id, startedAt, endedAt, deviceAddress, deviceName, weighting, timeWeighting) " +
                    "VALUES (1, 1700000000000, NULL, 'AA:BB:CC:DD:EE:FF', 'PCE-323', 'A', 'FAST')"
            )
            db.execSQL(
                "INSERT INTO measurements (id, sessionId, timestamp, levelDb, weighting, flags) " +
                    "VALUES (1, 1, 1700000000000, 55.5, 'A', 0)"
            )
            db.execSQL(
                "INSERT INTO minute_aggregates (id, sessionId, minuteStart, leqDb, maxDb, minDb, sampleCount, weighting) " +
                    "VALUES (1, 1, 1700000000000, 55.5, 60.0, 50.0, 118, 'A')"
            )
        }
    }

    private fun oeffneUeberProduktionspfad(context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, V10_DB_NAME)
            .addMigrations(*ALLE_MIGRATIONEN)
            .allowMainThreadQueries()
            .build()

    @Test
    fun bestandAusV10UeberlebtDieMigrationAufV11() {
        erzeugeV10Datenbank()
        val database = oeffneUeberProduktionspfad(ApplicationProvider.getApplicationContext())

        database.query("SELECT weighting, timeWeighting FROM sessions WHERE id = 1", null).use { cursor ->
            cursor.moveToFirst()
            assertEquals("A", cursor.getString(0))
            assertEquals("FAST", cursor.getString(1))
        }
        database.query("SELECT levelDb, weighting FROM measurements WHERE id = 1", null).use { cursor ->
            cursor.moveToFirst()
            assertEquals(55.5, cursor.getDouble(0), 0.0001)
            assertEquals("A", cursor.getString(1))
        }
        database.query("SELECT sampleCount FROM minute_aggregates WHERE id = 1", null).use { cursor ->
            cursor.moveToFirst()
            assertEquals(118, cursor.getInt(0))
        }

        database.close()
    }

    @Test
    fun neueSpaltenExistierenUndSindZunaechstNull() {
        erzeugeV10Datenbank()
        val database = oeffneUeberProduktionspfad(ApplicationProvider.getApplicationContext())

        database.query("SELECT range FROM sessions WHERE id = 1", null).use { cursor ->
            cursor.moveToFirst()
            assertNull(cursor.getString(0))
        }
        database.query("SELECT timeWeighting, range FROM measurements WHERE id = 1", null).use { cursor ->
            cursor.moveToFirst()
            assertNull(cursor.getString(0))
            assertNull(cursor.getString(1))
        }
        database.query("SELECT timeWeighting, range FROM minute_aggregates WHERE id = 1", null).use { cursor ->
            cursor.moveToFirst()
            assertNull(cursor.getString(0))
            assertNull(cursor.getString(1))
        }

        database.close()
    }

    @Test
    fun neueSpaltenSindBenutzbar() {
        erzeugeV10Datenbank()
        val database = oeffneUeberProduktionspfad(ApplicationProvider.getApplicationContext())

        database.openHelper.writableDatabase.execSQL(
            "UPDATE sessions SET range = 'RANGE_30_130' WHERE id = 1"
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE measurements SET timeWeighting = 'FAST', range = 'RANGE_30_130' WHERE id = 1"
        )

        database.query("SELECT range FROM sessions WHERE id = 1", null).use { cursor ->
            cursor.moveToFirst()
            assertEquals("RANGE_30_130", cursor.getString(0))
        }
        database.query("SELECT timeWeighting, range FROM measurements WHERE id = 1", null).use { cursor ->
            cursor.moveToFirst()
            assertEquals("FAST", cursor.getString(0))
            assertEquals("RANGE_30_130", cursor.getString(1))
        }

        database.close()
    }

    @Test
    fun zweitesOeffnenNachDerMigrationLaeuftDurch() {
        erzeugeV10Datenbank()
        val context = ApplicationProvider.getApplicationContext<Context>()

        oeffneUeberProduktionspfad(context).apply {
            query("SELECT COUNT(*) FROM measurements", null).use { it.moveToFirst() }
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
