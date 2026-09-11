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

private const val V18_DB_NAME = "v18-to-v19-migration-test"

/**
 * Migration 18 -> 19: eindeutiger Index auf `minute_aggregates(sessionId, minuteStart)`
 * (Prüfprotokoll-Anhang, Owner-Entscheidung vom 11.09.2026: "repariere es").
 *
 * Der Anlass: ein wiederholter Retention-Lauf konnte dieselbe Minute zweimal einfügen, ohne dass
 * die Datenbank das verhindert hätte. Diese Tests belegen zwei Dinge - dass ein Bestand MIT
 * bereits vorhandenen Duplikaten die Migration übersteht (Reduktion auf die jeweils letzte
 * Zeile), und dass danach ein erneuter `insertAll` über dieselbe Minute ersetzt statt dupliziert.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseV19MigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    private fun erzeugeV18DatenbankMitDuplikat() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbPath = context.getDatabasePath(V18_DB_NAME).path
        helper.createDatabase(dbPath, 18).use { db ->
            db.execSQL(
                "INSERT INTO sessions (id, startedAt, endedAt, deviceAddress, deviceName, weighting, timeWeighting, range) " +
                    "VALUES (9, 1700000000000, 1700003600000, 'AA:BB:CC:DD:EE:FF', 'PCE-323', NULL, NULL, NULL)"
            )
            // Zwei Zeilen fuer DIESELBE (sessionId, minuteStart)-Kombination - genau das
            // Duplikat, das vor dieser Migration entstehen konnte. Die zweite (hoehere id) traegt
            // absichtlich einen anderen leqDb-Wert, um pruefen zu koennen, dass die Migration
            // gezielt die JUENGERE Zeile behaelt, nicht irgendeine.
            db.execSQL(
                "INSERT INTO minute_aggregates (id, sessionId, minuteStart, leqDb, maxDb, minDb, sampleCount, weighting, timeWeighting, range) " +
                    "VALUES (1, 9, 1700000000000, 60.0, 65.0, 55.0, 100, NULL, NULL, NULL)"
            )
            db.execSQL(
                "INSERT INTO minute_aggregates (id, sessionId, minuteStart, leqDb, maxDb, minDb, sampleCount, weighting, timeWeighting, range) " +
                    "VALUES (2, 9, 1700000000000, 61.0, 66.0, 56.0, 100, NULL, NULL, NULL)"
            )
            db.execSQL(
                "INSERT INTO level_samples (id, at, levelDb, source) VALUES (1, 1700000000000, 58.0, 'MIKROFON')"
            )
        }
    }

    private fun oeffneUeberProduktionspfad(context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, V18_DB_NAME)
            .addMigrations(*ALLE_MIGRATIONEN)
            .allowMainThreadQueries()
            .build()

    @Test
    fun bestehendeDuplikateWerdenAufDieJuengsteZeileReduziert() {
        erzeugeV18DatenbankMitDuplikat()
        val database = oeffneUeberProduktionspfad(ApplicationProvider.getApplicationContext())

        runBlocking {
            val zeilen = database.minuteAggregateDao().fuerSession(9)
            assertEquals("Nach der Migration darf nur noch EINE Zeile je Minute uebrig sein", 1, zeilen.size)
            assertEquals("Behalten wird die zuletzt eingefuegte (hoehere id, id=2)", 61.0, zeilen.single().leqDb, 0.0001)
        }
        database.close()
    }

    @Test
    fun einErneuterInsertUeberDieselbeMinuteErsetztStattZuDuplizieren() {
        erzeugeV18DatenbankMitDuplikat()
        val database = oeffneUeberProduktionspfad(ApplicationProvider.getApplicationContext())

        runBlocking {
            val dao = database.minuteAggregateDao()
            dao.insertAll(
                listOf(
                    MinuteAggregateEntity(
                        sessionId = 9, minuteStart = 1700000000000, leqDb = 70.0, maxDb = 75.0, minDb = 65.0,
                        sampleCount = 120, weighting = null,
                    )
                )
            )

            val zeilen = dao.fuerSession(9)
            assertEquals("Ein zweiter Lauf ueber dieselbe Minute darf keine neue Zeile erzeugen", 1, zeilen.size)
            assertEquals(70.0, zeilen.single().leqDb, 0.0001)
        }
        database.close()
    }

    @Test
    fun levelSamplesUeberlebtDieMigrationMitDemNeuenIndex() {
        erzeugeV18DatenbankMitDuplikat()
        val database = oeffneUeberProduktionspfad(ApplicationProvider.getApplicationContext())

        runBlocking {
            val samples = database.levelSampleDao().zwischen(1_699_999_999_000L, 1_700_000_001_000L)
            assertEquals(1, samples.size)
            assertEquals(58.0, samples.single().levelDb, 0.0001)
        }
        database.close()
    }

    @Test
    fun eineNeueSessionOderMinuteBleibtEineEigeneZeile() {
        erzeugeV18DatenbankMitDuplikat()
        val database = oeffneUeberProduktionspfad(ApplicationProvider.getApplicationContext())

        runBlocking {
            val dao = database.minuteAggregateDao()
            dao.insertAll(
                listOf(
                    MinuteAggregateEntity(
                        sessionId = 9, minuteStart = 1700000060000, leqDb = 62.0, maxDb = 67.0, minDb = 57.0,
                        sampleCount = 100, weighting = null,
                    )
                )
            )

            assertEquals(
                "Eine andere Minute derselben Session ist keine Kollision",
                2,
                dao.fuerSession(9).size,
            )
            assertTrue(dao.fuerSession(9).any { it.minuteStart == 1700000060000L })
        }
        database.close()
    }
}
