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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val V20_DB_NAME = "v20-to-v21-migration-test"

/**
 * Migration 20 -> 21 (Bericht-Umbau Schritt 2, High-End-Bericht via Chaquopy): neue Spalten
 * `sessions.rohdatenPruefsumme` und `dokumentationsfotos.geometrieTag`, neue Tabelle
 * `report_config` fuer die § 287 ZPO-Schaetzparameter.
 *
 * Rein additiv - der Test belegt, dass bestehende Sessions und Fotos die Migration unveraendert
 * ueberstehen (neue Spalten sind fuer sie NULL) und die neue Tabelle danach ueber die Produktions-
 * DAOs nutzbar ist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseV21MigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    private fun erzeugeV20Datenbank() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbPath = context.getDatabasePath(V20_DB_NAME).path
        helper.createDatabase(dbPath, 20).use { db ->
            db.execSQL(
                "INSERT INTO sessions (id, startedAt, endedAt, deviceAddress, deviceName, weighting, timeWeighting, range) " +
                    "VALUES (1, 1000, 9000, 'AA:BB:CC:DD:EE:FF', 'PCE-323', NULL, NULL, NULL)"
            )
            db.execSQL(
                "INSERT INTO dokumentationsfotos (id, sessionId, kategorie, dateiPfad, aufgenommenAm, notiz, driveFileId, pruefsumme) " +
                    "VALUES (1, 1, 'MESSAUFBAU', '/beweis.jpg', 1500, NULL, NULL, 'abc123')"
            )
        }
    }

    private fun oeffneUeberProduktionspfad(context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, V20_DB_NAME)
            .addMigrations(*ALLE_MIGRATIONEN)
            .allowMainThreadQueries()
            .build()

    @Test
    fun bestehendeSessionsUndFotosUeberlebenDieMigrationMitLeererNeuerSpalte() {
        erzeugeV20Datenbank()
        val database = oeffneUeberProduktionspfad(ApplicationProvider.getApplicationContext())

        runBlocking {
            val session = database.sessionDao().byId(1)
            assertEquals(1L, session?.id)
            assertNull("rohdatenPruefsumme muss fuer Altsessions NULL sein", session?.rohdatenPruefsumme)

            val fotos = database.dokumentationsFotoDao().fuerSession(1)
            assertEquals(1, fotos.size)
            assertNull("geometrieTag muss fuer Altfotos NULL sein", fotos.first().geometrieTag)
            assertEquals("abc123", fotos.first().pruefsumme)
        }
        database.close()
    }

    @Test
    fun reportConfigTabelleIstNachDerMigrationUeberDasDaoNutzbar() {
        erzeugeV20Datenbank()
        val database = oeffneUeberProduktionspfad(ApplicationProvider.getApplicationContext())

        runBlocking {
            assertNull("vor dem ersten Speichern gibt es noch keine Konfiguration", database.reportConfigDao().get())

            val config = ReportConfigEntity(
                schaetzpegelTeilerfassungDb = 52.0,
                schaetzpegelMessfensterAbbruchDb = 57.0,
                tierSchwelleVollmessungProzent = 92.0,
                tierSchwelleTeilerfassungProzent = 65.0,
                adresse = "Musterstraße 1, 12345 Musterstadt",
                gebietseinstufung = "WA",
                hardwareId = "PCE-323-000123",
                geraeteUnsicherheitDb = 1.4,
            )
            database.reportConfigDao().speichere(config)

            val geladen = database.reportConfigDao().get()
            assertEquals(52.0, geladen?.schaetzpegelTeilerfassungDb ?: 0.0, 0.0001)
            assertEquals("WA", geladen?.gebietseinstufung)

            // Erneutes Speichern ersetzt die einzelne Zeile (Singleton), statt eine zweite anzulegen.
            database.reportConfigDao().speichere(config.copy(gebietseinstufung = "WR"))
            assertEquals("WR", database.reportConfigDao().get()?.gebietseinstufung)
        }
        database.close()
    }
}
