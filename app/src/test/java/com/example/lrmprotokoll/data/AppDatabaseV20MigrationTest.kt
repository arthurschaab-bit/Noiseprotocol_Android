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

private const val V19_DB_NAME = "v19-to-v20-migration-test"

/**
 * Migration 19 -> 20: Indizes auf `measurements(sessionId, timestamp)` und `measurements(timestamp)`
 * (Prüfprotokoll-Anhang C-4-Rest, Owner-Entscheidung vom 11.09.2026: "just do it").
 *
 * Anlass: [RetentionCoordinator.verdichte] und
 * [com.example.lrmprotokoll.messreihe.MeasurementRecorder.schliesseVerwaisteSessions] fragen
 * `measurements` regelmäßig nach `sessionId` bzw. `timestamp` ab - ohne Index war das ein
 * Table-Scan über die per C-4 jetzt bewusst 90 Tage aufbewahrten Rohwerte. Reine Index-Migration,
 * keine Datenumformung nötig - der Test belegt, dass bestehende Zeilen die Migration überstehen
 * und die neue, gruppierte Abfrage danach funktioniert.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseV20MigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    private fun erzeugeV19Datenbank() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbPath = context.getDatabasePath(V19_DB_NAME).path
        helper.createDatabase(dbPath, 19).use { db ->
            db.execSQL(
                "INSERT INTO sessions (id, startedAt, endedAt, deviceAddress, deviceName, weighting, timeWeighting, range) " +
                    "VALUES (9, 1000, 9000, 'AA:BB:CC:DD:EE:FF', 'PCE-323', NULL, NULL, NULL)"
            )
            db.execSQL(
                "INSERT INTO sessions (id, startedAt, endedAt, deviceAddress, deviceName, weighting, timeWeighting, range) " +
                    "VALUES (10, 2000, NULL, 'AA:BB:CC:DD:EE:FF', 'PCE-323', NULL, NULL, NULL)"
            )
            db.execSQL(
                "INSERT INTO measurements (id, sessionId, timestamp, levelDb, weighting, flags) " +
                    "VALUES (1, 9, 5000, 60.0, NULL, 0)"
            )
            db.execSQL(
                "INSERT INTO measurements (id, sessionId, timestamp, levelDb, weighting, flags) " +
                    "VALUES (2, 9, 9000, 61.0, NULL, 0)"
            )
            db.execSQL(
                "INSERT INTO measurements (id, sessionId, timestamp, levelDb, weighting, flags) " +
                    "VALUES (3, 10, 6000, 62.0, NULL, 0)"
            )
        }
    }

    private fun oeffneUeberProduktionspfad(context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, V19_DB_NAME)
            .addMigrations(*ALLE_MIGRATIONEN)
            .allowMainThreadQueries()
            .build()

    @Test
    fun bestehendeMesswerteUeberlebenDieMigration() {
        erzeugeV19Datenbank()
        val database = oeffneUeberProduktionspfad(ApplicationProvider.getApplicationContext())

        runBlocking {
            val werte = database.measurementDao().fuerSession(9)
            assertEquals(2, werte.size)
            assertEquals(61.0, werte.maxOf { it.levelDb }, 0.0001)
        }
        database.close()
    }

    @Test
    fun beideIndizesWerdenAngelegt() {
        erzeugeV19Datenbank()
        val database = oeffneUeberProduktionspfad(ApplicationProvider.getApplicationContext())

        val indexNamen = mutableSetOf<String>()
        database.query("PRAGMA index_list('measurements')", null).use { cursor ->
            val nameSpalte = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                indexNamen += cursor.getString(nameSpalte)
            }
        }

        assertTrue(
            "sessionId+timestamp-Index fehlt: $indexNamen",
            indexNamen.contains("index_measurements_sessionId_timestamp"),
        )
        assertTrue(
            "timestamp-Index fehlt: $indexNamen",
            indexNamen.contains("index_measurements_timestamp"),
        )
        database.close()
    }

    @Test
    fun letzteZeitstempelJeSessionGruppiertKorrektNachDerMigration() {
        erzeugeV19Datenbank()
        val database = oeffneUeberProduktionspfad(ApplicationProvider.getApplicationContext())

        runBlocking {
            val ergebnis = database.measurementDao().letzteZeitstempelJeSession(listOf(9, 10))
                .associate { it.sessionId to it.letzterZeitstempel }

            assertEquals(9000L, ergebnis[9])
            assertEquals(6000L, ergebnis[10])
        }
        database.close()
    }
}
