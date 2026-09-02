package com.example.lrmprotokoll.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val V14_DB_NAME = "v14-to-v15-migration-test"

/**
 * Migration 14 -> 15 (M11 Etappe A): neue Tabelle `dokumentationsfotos`.
 *
 * Rein additiv - der Test belegt beides: dass vorhandene Daten die Migration unveraendert
 * ueberstehen, und dass die neue Tabelle danach benutzbar ist. Eine Migration, die nur
 * "wirft nicht" beweist, ist keine geprueft.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseV15MigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    private fun erzeugeV14Datenbank() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbPath = context.getDatabasePath(V14_DB_NAME).path
        helper.createDatabase(dbPath, 14).use { db ->
            // NOT-NULL-Spalten ohne SQL-Default muessen ausdruecklich gefuellt werden, sonst
            // scheitert schon das INSERT an einer SQLiteConstraintException.
            db.execSQL(
                "INSERT INTO noise_records (id, timestamp, amplitude, dbValue, filePath, label, detectedLabel, " +
                    "calibratedDbA, meterWeighting, meterConnected, isQuietHour, deletedAt, favorite, notes) " +
                    "VALUES (1, 1700000000000, 1000.0, 55.5, '/path/audio1.wav', 'Bohren', 'Bohren', " +
                    "58.2, 'A', 1, 0, NULL, 0, NULL)"
            )
            db.execSQL(
                "INSERT INTO sessions (id, startedAt, endedAt, deviceAddress, deviceName, weighting, timeWeighting, range) " +
                    "VALUES (7, 1700000000000, 1700000600000, 'AA:BB:CC:DD:EE:FF', 'PCE-323', NULL, NULL, NULL)"
            )
        }
    }

    private fun oeffneUeberProduktionspfad(context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, V14_DB_NAME)
            .addMigrations(*ALLE_MIGRATIONEN)
            .allowMainThreadQueries()
            .build()

    @Test
    fun bestandAusV14UeberlebtDieMigrationAufV15() {
        erzeugeV14Datenbank()
        val database = oeffneUeberProduktionspfad(ApplicationProvider.getApplicationContext())

        database.query("SELECT dbValue, calibratedDbA, meterWeighting FROM noise_records WHERE id = 1", null).use { cursor ->
            assertTrue("Der Datensatz aus v14 muss noch da sein", cursor.moveToFirst())
            assertEquals(55.5, cursor.getDouble(0), 0.001)
            assertEquals(58.2, cursor.getDouble(1), 0.001)
            assertEquals("A", cursor.getString(2))
        }
        database.query("SELECT deviceName FROM sessions WHERE id = 7", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("PCE-323", cursor.getString(0))
        }
        database.close()
    }

    @Test
    fun dieNeueTabelleIstNachDerMigrationBenutzbar() {
        erzeugeV14Datenbank()
        val database = oeffneUeberProduktionspfad(ApplicationProvider.getApplicationContext())

        runBlocking {
            val id = database.dokumentationsFotoDao().insert(
                DokumentationsFotoEntity(
                    sessionId = 7,
                    kategorie = FotoKategorie.MESSAUFBAU.name,
                    dateiPfad = "/pfad/foto.jpg",
                    aufgenommenAm = 1700000100000,
                    notiz = "Messgerät 1,5 m über Boden",
                    pruefsumme = "abc123",
                )
            )
            val gelesen = database.dokumentationsFotoDao().byId(id)
            assertEquals(7L, gelesen?.sessionId)
            assertEquals(FotoKategorie.MESSAUFBAU.name, gelesen?.kategorie)
            assertEquals("Messgerät 1,5 m über Boden", gelesen?.notiz)
            assertEquals("abc123", gelesen?.pruefsumme)
            assertNull("Vor dem Upload gibt es keine Drive-ID", gelesen?.driveFileId)

            // Genau diese Abfrage steuert den Drive-Upload: nur Fotos ohne driveFileId.
            assertEquals(1, database.dokumentationsFotoDao().nichtHochgeladene().size)
            database.dokumentationsFotoDao().setzeDriveFileId(id, "drive-1")
            assertTrue(database.dokumentationsFotoDao().nichtHochgeladene().isEmpty())
        }
        database.close()
    }
}
