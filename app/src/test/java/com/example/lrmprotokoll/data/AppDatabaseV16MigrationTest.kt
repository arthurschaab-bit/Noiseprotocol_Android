package com.example.lrmprotokoll.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val V15_DB_NAME = "v15-to-v16-migration-test"

/**
 * Migration 15 -> 16 (M11 Etappe B): neue Tabelle `beweisvideos`.
 *
 * Rein additiv - der Test belegt beides: dass vorhandene Daten die Migration unveraendert
 * ueberstehen, und dass die neue Tabelle danach benutzbar ist. Eine Migration, die nur
 * "wirft nicht" beweist, ist keine geprueft.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseV16MigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    private fun erzeugeV15Datenbank() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbPath = context.getDatabasePath(V15_DB_NAME).path
        helper.createDatabase(dbPath, 15).use { db ->
            db.execSQL(
                "INSERT INTO sessions (id, startedAt, endedAt, deviceAddress, deviceName, weighting, timeWeighting, range) " +
                    "VALUES (7, 1700000000000, 1700000600000, 'AA:BB:CC:DD:EE:FF', 'PCE-323', NULL, NULL, NULL)"
            )
            // Die in v15 angelegte Fototabelle muss die naechste Migration ebenfalls ueberstehen.
            db.execSQL(
                "INSERT INTO dokumentationsfotos (id, sessionId, kategorie, dateiPfad, aufgenommenAm, notiz, driveFileId, pruefsumme) " +
                    "VALUES (1, 7, 'MESSAUFBAU', '/pfad/foto.jpg', 1700000100000, NULL, NULL, 'abc123')"
            )
        }
    }

    private fun oeffneUeberProduktionspfad(context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, V15_DB_NAME)
            .addMigrations(*ALLE_MIGRATIONEN)
            .allowMainThreadQueries()
            .build()

    @Test
    fun bestandAusV15UeberlebtDieMigrationAufV16() {
        erzeugeV15Datenbank()
        val database = oeffneUeberProduktionspfad(ApplicationProvider.getApplicationContext())

        database.query("SELECT deviceName FROM sessions WHERE id = 7", null).use { cursor ->
            assertTrue("Die Session aus v15 muss noch da sein", cursor.moveToFirst())
            assertEquals("PCE-323", cursor.getString(0))
        }
        database.query("SELECT kategorie, pruefsumme FROM dokumentationsfotos WHERE id = 1", null).use { cursor ->
            assertTrue("Das Foto aus v15 muss noch da sein", cursor.moveToFirst())
            assertEquals("MESSAUFBAU", cursor.getString(0))
            assertEquals("abc123", cursor.getString(1))
        }
        database.close()
    }

    @Test
    fun dieNeueTabelleIstNachDerMigrationBenutzbar() {
        erzeugeV15Datenbank()
        val database = oeffneUeberProduktionspfad(ApplicationProvider.getApplicationContext())

        runBlocking {
            val dao = database.beweisVideoDao()
            val id = dao.insert(
                BeweisVideoEntity(
                    sessionId = 7,
                    dateiPfad = "/pfad/video_stumm.mp4",
                    gestartetAm = 1700000200000,
                    dauerMs = 42_000,
                    hatTonspur = false,
                    groesseBytes = 12_345_678,
                    notiz = "Presslufthammer vor dem Haus",
                )
            )
            val gelesen = dao.byId(id)
            assertEquals(7L, gelesen?.sessionId)
            assertEquals(42_000L, gelesen?.dauerMs)
            assertEquals("Presslufthammer vor dem Haus", gelesen?.notiz)
            assertNull("Vor dem Upload gibt es keine Drive-ID", gelesen?.driveFileId)
            assertFalse("Frisch aufgenommen ist noch nichts gemuxt", gelesen?.tonGemuxt ?: true)
            assertEquals(1, dao.fuerSession(7).size)
        }
        database.close()
    }

    @Test
    fun nichtHochgeladeneLiefertNurFertigGemuxteVideos() {
        // Genau diese Abfrage steuert den Drive-Upload. Ein noch nicht gemuxtes Video darf sie
        // nicht liefern - sonst landet die stumme Zwischenfassung in Drive.
        erzeugeV15Datenbank()
        val database = oeffneUeberProduktionspfad(ApplicationProvider.getApplicationContext())

        runBlocking {
            val dao = database.beweisVideoDao()
            val ungemuxt = dao.insert(video(gestartetAm = 1700000200000))
            val gemuxt = dao.insert(video(gestartetAm = 1700000300000))
            dao.setzeGemuxt(gemuxt, "/pfad/video_final.mp4", groesseBytes = 13_000_000, hatTonspur = true)

            assertEquals(listOf(gemuxt), dao.nichtHochgeladene().map { it.id })
            assertEquals(listOf(ungemuxt), dao.ungemuxte().map { it.id })

            dao.setzeDriveFileId(gemuxt, "drive-1")
            assertTrue("Nach dem Upload bleibt nichts offen", dao.nichtHochgeladene().isEmpty())
        }
        database.close()
    }

    @Test
    fun uploadFortschrittUeberlebtEinenNeustart() {
        // Der Session-URI wird persistiert, BEVOR der erste Block rausgeht - sonst faengt ein
        // Prozess-Neustart mitten im Upload wieder bei null an.
        erzeugeV15Datenbank()
        val database = oeffneUeberProduktionspfad(ApplicationProvider.getApplicationContext())

        runBlocking {
            val dao = database.beweisVideoDao()
            val id = dao.insert(video(gestartetAm = 1700000200000))
            dao.setzeUploadFortschritt(id, "https://drive.example/session/abc", 8 * 1024 * 1024)

            val gelesen = dao.byId(id)
            assertEquals("https://drive.example/session/abc", gelesen?.uploadSessionUri)
            assertEquals(8L * 1024 * 1024, gelesen?.hochgeladeneBytes)
        }
        database.close()
    }

    private fun video(gestartetAm: Long) = BeweisVideoEntity(
        sessionId = 7,
        dateiPfad = "/pfad/video_$gestartetAm.mp4",
        gestartetAm = gestartetAm,
        dauerMs = 30_000,
        hatTonspur = false,
        groesseBytes = 10_000_000,
    )
}
