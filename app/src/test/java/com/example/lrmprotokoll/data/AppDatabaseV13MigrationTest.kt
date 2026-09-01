package com.example.lrmprotokoll.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val V12_DB_NAME = "v12-to-v13-migration-test"

/**
 * Migration 12 -> 13 (KI-Umbau Etappe 1): vier neue nullable Spalten auf `noise_records` und die
 * neue Tabelle `klassifikations_rohdaten` mit Fremdschluessel auf `noise_records.id` (CASCADE).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseV13MigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    private fun erzeugeV12Datenbank() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbPath = context.getDatabasePath(V12_DB_NAME).path
        helper.createDatabase(dbPath, 12).use { db ->
            db.execSQL(
                "INSERT INTO noise_records (id, timestamp, amplitude, dbValue, filePath, label, detectedLabel, " +
                    "calibratedDbA, meterWeighting, meterConnected, isQuietHour, deletedAt, favorite, notes) " +
                    "VALUES (1, 1700000000000, 1000.0, 55.5, '/path/audio1.wav', 'Bohren', 'Bohren', " +
                    "58.2, 'A', 1, 0, NULL, 0, NULL)"
            )
        }
    }

    private fun oeffneUeberProduktionspfad(context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, V12_DB_NAME)
            .addMigrations(*ALLE_MIGRATIONEN)
            .allowMainThreadQueries()
            .build()

    @Test
    fun bestandAusV12UeberlebtDieMigrationAufV13() {
        erzeugeV12Datenbank()
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
    fun neueNoiseRecordSpaltenExistierenUndSindNullFuerAltbestand() {
        erzeugeV12Datenbank()
        val database = oeffneUeberProduktionspfad(ApplicationProvider.getApplicationContext())

        database.query(
            "SELECT aufnahmeQuelle, abtastrate, kanalzahl, agcAktiv FROM noise_records WHERE id = 1", null,
        ).use { cursor ->
            cursor.moveToFirst()
            assertNull(cursor.getString(0))
            assertNull(cursor.getString(1))
            assertNull(cursor.getString(2))
            assertNull(cursor.getString(3))
        }

        database.close()
    }

    @Test
    fun klassifikationsRohdatenTabelleExistiertUndIstBeschreibbar() {
        erzeugeV12Datenbank()
        val database = oeffneUeberProduktionspfad(ApplicationProvider.getApplicationContext())

        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO klassifikations_rohdaten " +
                "(recordId, modellVersion, klassifiziertAm, frameAnzahl, frameDauerMs, frameHopMs, " +
                "klassenIndizes, frameScores, topKlassen) " +
                "VALUES (1, 'yamnet.tflite@abc123', 1700000100000, 3, 960, 480, '412,413', X'0102030405', '[]')"
        )

        database.query(
            "SELECT recordId, modellVersion, frameAnzahl, klassenIndizes FROM klassifikations_rohdaten WHERE recordId = 1",
            null,
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals(1L, cursor.getLong(0))
            assertEquals("yamnet.tflite@abc123", cursor.getString(1))
            assertEquals(3, cursor.getInt(2))
            assertEquals("412,413", cursor.getString(3))
        }

        database.close()
    }

    @Test
    fun loeschenEinerAufnahmeLoeschtIhreRohdatenPerCascade() {
        erzeugeV12Datenbank()
        val database = oeffneUeberProduktionspfad(ApplicationProvider.getApplicationContext())
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO klassifikations_rohdaten " +
                "(recordId, modellVersion, klassifiziertAm, frameAnzahl, frameDauerMs, frameHopMs, " +
                "klassenIndizes, frameScores, topKlassen) " +
                "VALUES (1, 'yamnet.tflite@abc123', 1700000100000, 1, 960, 480, '412', X'01', '[]')"
        )

        database.openHelper.writableDatabase.execSQL("DELETE FROM noise_records WHERE id = 1")

        database.query("SELECT COUNT(*) FROM klassifikations_rohdaten", null).use { cursor ->
            cursor.moveToFirst()
            assertEquals(
                "Ein geloeschtes Beweismittel darf keine verwaisten Rohdatensaetze hinterlassen",
                0, cursor.getInt(0),
            )
        }

        database.close()
    }
}
