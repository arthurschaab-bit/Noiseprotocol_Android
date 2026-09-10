package com.example.lrmprotokoll.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val V17_DB_NAME = "v17-to-v18-migration-test"

/**
 * Migration 17 -> 18: neue Tabelle `stammdaten_verlauf` (Gesamtbericht-Stammdaten beim
 * Messbeginn statt fest in den Einstellungen, Owner-Anfrage 10.09.2026).
 *
 * Rein additiv wie die Migrationen zuvor - bestehende Tabellen bleiben unangetastet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseV18MigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    private fun erzeugeV17Datenbank() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbPath = context.getDatabasePath(V17_DB_NAME).path
        helper.createDatabase(dbPath, 17).use { db ->
            db.execSQL(
                "INSERT INTO sessions (id, startedAt, endedAt, deviceAddress, deviceName, weighting, timeWeighting, range) " +
                    "VALUES (9, 1700000000000, 1700000600000, 'AA:BB:CC:DD:EE:FF', 'PCE-323', NULL, NULL, NULL)"
            )
        }
    }

    private fun oeffneUeberProduktionspfad(context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, V17_DB_NAME)
            .addMigrations(*ALLE_MIGRATIONEN)
            .allowMainThreadQueries()
            .build()

    @Test
    fun bestandAusV17UeberlebtDieMigrationAufV18() {
        erzeugeV17Datenbank()
        val database = oeffneUeberProduktionspfad(ApplicationProvider.getApplicationContext())

        runBlocking {
            val session = database.sessionDao().byId(9)
            assertEquals("PCE-323", session?.deviceName)
            assertTrue("Die neue Tabelle ist zunaechst leer", database.stammdatenVerlaufDao().letzte(10).isEmpty())
        }
        database.close()
    }

    @Test
    fun neuerStammdatenEintragLaesstSichAnlegenUndWiederAuslesen() {
        erzeugeV17Datenbank()
        val database = oeffneUeberProduktionspfad(ApplicationProvider.getApplicationContext())

        runBlocking {
            val dao = database.stammdatenVerlaufDao()
            dao.insert(
                StammdatenVerlaufEntity(
                    erstelltAm = 1700000100000,
                    geraetHersteller = "PCE Instruments",
                    geraetTyp = "PCE-323",
                    geraetGenauigkeitsklasse = "Klasse 2",
                    geraetSeriennummer = "123456",
                    geraetKalibrierung = "94 dB(A) mit Kalibrator XY",
                    messort = "Musterstraße 1",
                    mikrofonposition = "Balkon",
                    mikrofonhoehe = "1,5 m",
                    entfernungZurQuelle = "10 m",
                    innenAussen = "Außen",
                    fensterzustand = "",
                    wetter = "12 °C, bedeckt",
                    datenqualitaetHinweis = "",
                )
            )

            val letzte = dao.letzte(10)
            assertEquals(1, letzte.size)
            assertEquals("Musterstraße 1", letzte.single().messort)
        }
        database.close()
    }
}
