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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val V16_DB_NAME = "v16-to-v17-migration-test"

/**
 * Migration 16 -> 17: neue Spalte `muxFehlgeschlagen` in `beweisvideos`.
 *
 * Der Anlass ist ein am Geraet gemeldeter Fehler: Ohne Endzustand fuer einen gescheiterten
 * Mux-Lauf zeigte die Oberflaeche "Ton wird hinzugefuegt ..." unbegrenzt weiter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseV17MigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    private fun erzeugeV16Datenbank() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbPath = context.getDatabasePath(V16_DB_NAME).path
        helper.createDatabase(dbPath, 16).use { db ->
            db.execSQL(
                "INSERT INTO sessions (id, startedAt, endedAt, deviceAddress, deviceName, weighting, timeWeighting, range) " +
                    "VALUES (7, 1700000000000, 1700000600000, 'AA:BB:CC:DD:EE:FF', 'PCE-323', NULL, NULL, NULL)"
            )
            db.execSQL(
                "INSERT INTO beweisvideos (id, sessionId, dateiPfad, gestartetAm, dauerMs, hatTonspur, " +
                    "groesseBytes, notiz, tonGemuxt, driveFileId, uploadSessionUri, hochgeladeneBytes) " +
                    "VALUES (1, 7, '/pfad/video.mp4', 1700000200000, 30000, 1, 4096, NULL, 1, NULL, NULL, 0)"
            )
        }
    }

    private fun oeffneUeberProduktionspfad(context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, V16_DB_NAME)
            .addMigrations(*ALLE_MIGRATIONEN)
            .allowMainThreadQueries()
            .build()

    @Test
    fun bestandAusV16UeberlebtDieMigrationAufV17() {
        erzeugeV16Datenbank()
        val database = oeffneUeberProduktionspfad(ApplicationProvider.getApplicationContext())

        runBlocking {
            val video = database.beweisVideoDao().byId(1)!!
            assertEquals("/pfad/video.mp4", video.dateiPfad)
            assertEquals(30_000L, video.dauerMs)
            assertTrue("Ein fertig gemuxtes Video bleibt fertig", video.tonGemuxt)
            assertFalse("Bestehende Zeilen sind per Definition nicht gescheitert", video.muxFehlgeschlagen)
        }
        database.close()
    }

    @Test
    fun einGescheiterterMuxLaufIstEinEndzustand() {
        erzeugeV16Datenbank()
        val database = oeffneUeberProduktionspfad(ApplicationProvider.getApplicationContext())

        runBlocking {
            val dao = database.beweisVideoDao()
            val id = dao.insert(
                BeweisVideoEntity(
                    sessionId = 7,
                    dateiPfad = "/pfad/stumm.mp4",
                    gestartetAm = 1700000300000,
                    dauerMs = 20_000,
                    hatTonspur = false,
                    groesseBytes = 2048,
                )
            )
            assertEquals("Vor dem Lauf steht das Video zur Verarbeitung an", listOf(id), dao.ungemuxte().map { it.id })

            dao.setzeMuxFehlgeschlagen(id)

            assertTrue("Gescheitert heisst: kein weiterer Versuch", dao.ungemuxte().isEmpty())
            assertTrue("Und kein Upload - hochgeladen wird nur, was fertig ist", dao.nichtHochgeladene().none { it.id == id })
            assertTrue(dao.byId(id)!!.muxFehlgeschlagen)
        }
        database.close()
    }

    @Test
    fun einErfolgreicherZweiterAnlaufHebtDenFehlerzustandAuf() {
        erzeugeV16Datenbank()
        val database = oeffneUeberProduktionspfad(ApplicationProvider.getApplicationContext())

        runBlocking {
            val dao = database.beweisVideoDao()
            val id = dao.insert(
                BeweisVideoEntity(
                    sessionId = 7,
                    dateiPfad = "/pfad/stumm.mp4",
                    gestartetAm = 1700000300000,
                    dauerMs = 20_000,
                    hatTonspur = false,
                    groesseBytes = 2048,
                )
            )
            dao.setzeMuxFehlgeschlagen(id)
            dao.setzeGemuxt(id, "/pfad/fertig.mp4", groesseBytes = 5000, hatTonspur = true)

            val video = dao.byId(id)!!
            assertTrue(video.tonGemuxt)
            assertFalse("Ein gelungener Lauf laesst keinen Fehlerzustand zurueck", video.muxFehlgeschlagen)
        }
        database.close()
    }
}
