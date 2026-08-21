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

private const val V11_DB_NAME = "v11-to-v12-migration-test"

/**
 * Migration 11 -> 12: `isQuietHour`, `deletedAt`, `favorite`, `notes` auf `noise_records`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseV12MigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    private fun erzeugeV11Datenbank() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbPath = context.getDatabasePath(V11_DB_NAME).path
        helper.createDatabase(dbPath, 11).use { db ->
            db.execSQL(
                "INSERT INTO noise_records (id, timestamp, amplitude, dbValue, filePath, label, detectedLabel, calibratedDbA, meterWeighting, meterConnected) " +
                    "VALUES (1, 1700000000000, 1000.0, 55.5, '/path/audio1.wav', 'Bohren', 'Bohren', 58.2, 'A', 1)"
            )
        }
    }

    private fun oeffneUeberProduktionspfad(context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, V11_DB_NAME)
            .addMigrations(*ALLE_MIGRATIONEN)
            .allowMainThreadQueries()
            .build()

    @Test
    fun bestandAusV11UeberlebtDieMigrationAufV12() {
        erzeugeV11Datenbank()
        val database = oeffneUeberProduktionspfad(ApplicationProvider.getApplicationContext())

        database.query("SELECT dbValue, calibratedDbA, meterConnected FROM noise_records WHERE id = 1", null).use { cursor ->
            cursor.moveToFirst()
            assertEquals(55.5, cursor.getDouble(0), 0.0001)
            assertEquals(58.2, cursor.getDouble(1), 0.0001)
            assertEquals(1, cursor.getInt(2))
        }

        database.close()
    }

    @Test
    fun neueSpaltenExistierenMitDefaultWerten() {
        erzeugeV11Datenbank()
        val database = oeffneUeberProduktionspfad(ApplicationProvider.getApplicationContext())

        database.query("SELECT isQuietHour, deletedAt, favorite, notes FROM noise_records WHERE id = 1", null).use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
            assertNull(cursor.getString(1))
            assertEquals(0, cursor.getInt(2))
            assertNull(cursor.getString(3))
        }

        database.close()
    }

    @Test
    fun neueSpaltenSindBeschreibbar() {
        erzeugeV11Datenbank()
        val database = oeffneUeberProduktionspfad(ApplicationProvider.getApplicationContext())

        database.openHelper.writableDatabase.execSQL(
            "UPDATE noise_records SET isQuietHour = 1, deletedAt = 1700000100000, favorite = 1, notes = 'Sehr lauter Nachbar' WHERE id = 1"
        )

        database.query("SELECT isQuietHour, deletedAt, favorite, notes FROM noise_records WHERE id = 1", null).use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
            assertEquals(1700000100000L, cursor.getLong(1))
            assertEquals(1, cursor.getInt(2))
            assertEquals("Sehr lauter Nachbar", cursor.getString(3))
        }

        database.close()
    }
}
