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

private const val V13_DB_NAME = "v13-to-v14-migration-test"

/**
 * Migration 13 -> 14 (KI-Umbau Etappe 3.4): fünf neue nullable Spalten auf
 * `klassifikations_rohdaten` für die physikalischen Hüllkurven-Merkmale (Impulsanalyse).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseV14MigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    private fun erzeugeV13Datenbank() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbPath = context.getDatabasePath(V13_DB_NAME).path
        helper.createDatabase(dbPath, 13).use { db ->
            db.execSQL(
                "INSERT INTO noise_records (id, timestamp, amplitude, dbValue, filePath, label, detectedLabel, " +
                    "meterConnected, isQuietHour, favorite) " +
                    "VALUES (1, 1700000000000, 1000.0, 55.5, '/path/audio1.wav', NULL, 'Baulärm · 40% · Spitze: Hämmern', " +
                    "0, 0, 0)"
            )
            db.execSQL(
                "INSERT INTO klassifikations_rohdaten " +
                    "(recordId, modellVersion, klassifiziertAm, frameAnzahl, frameDauerMs, frameHopMs, " +
                    "klassenIndizes, frameScores, topKlassen) " +
                    "VALUES (1, 'yamnet.tflite@abc123', 1700000100000, 3, 960, 975, '412,413', X'0102030405', '[]')"
            )
        }
    }

    private fun oeffneUeberProduktionspfad(context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, V13_DB_NAME)
            .addMigrations(*ALLE_MIGRATIONEN)
            .allowMainThreadQueries()
            .build()

    @Test
    fun bestandAusV13UeberlebtDieMigrationAufV14() {
        erzeugeV13Datenbank()
        val database = oeffneUeberProduktionspfad(ApplicationProvider.getApplicationContext())

        database.query(
            "SELECT modellVersion, frameAnzahl, klassenIndizes FROM klassifikations_rohdaten WHERE recordId = 1",
            null,
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals("yamnet.tflite@abc123", cursor.getString(0))
            assertEquals(3, cursor.getInt(1))
            assertEquals("412,413", cursor.getString(2))
        }

        database.close()
    }

    @Test
    fun neueImpulsSpaltenExistierenUndSindNullFuerAltbestand() {
        erzeugeV13Datenbank()
        val database = oeffneUeberProduktionspfad(ApplicationProvider.getApplicationContext())

        database.query(
            "SELECT impulsCrest, impulsKurtosis, impulsWiederholrateHz, impulsPeakSchaerfe, impulsMittlererPegel " +
                "FROM klassifikations_rohdaten WHERE recordId = 1",
            null,
        ).use { cursor ->
            cursor.moveToFirst()
            assertNull(cursor.getString(0))
            assertNull(cursor.getString(1))
            assertNull(cursor.getString(2))
            assertNull(cursor.getString(3))
            assertNull(cursor.getString(4))
        }

        database.close()
    }

    @Test
    fun neueImpulsSpaltenSindBeschreibbar() {
        erzeugeV13Datenbank()
        val database = oeffneUeberProduktionspfad(ApplicationProvider.getApplicationContext())

        database.openHelper.writableDatabase.execSQL(
            "UPDATE klassifikations_rohdaten SET impulsCrest = 4.2, impulsKurtosis = 12.5, " +
                "impulsWiederholrateHz = 14.3, impulsPeakSchaerfe = 6.1, impulsMittlererPegel = 0.08 " +
                "WHERE recordId = 1"
        )

        database.query(
            "SELECT impulsCrest, impulsKurtosis, impulsWiederholrateHz, impulsPeakSchaerfe, impulsMittlererPegel " +
                "FROM klassifikations_rohdaten WHERE recordId = 1",
            null,
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals(4.2, cursor.getDouble(0), 0.001)
            assertEquals(12.5, cursor.getDouble(1), 0.001)
            assertEquals(14.3, cursor.getDouble(2), 0.001)
            assertEquals(6.1, cursor.getDouble(3), 0.001)
            assertEquals(0.08, cursor.getDouble(4), 0.001)
        }

        database.close()
    }
}
