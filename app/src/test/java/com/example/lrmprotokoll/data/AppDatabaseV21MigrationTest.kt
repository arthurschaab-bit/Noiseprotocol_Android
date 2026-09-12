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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val V20_DB_NAME = "v20-to-v21-migration-test"

/**
 * Migration 20 -> 21 (Owner-Feature-Auftrag 12.09.2026): neue Spalte
 * `dokumentationsfotos.nachtraeglichHinzugefuegt`, damit ein spaeter aus der Foto-Galerie
 * importiertes Foto von einem waehrend der Messung aufgenommenen Kamerafoto unterscheidbar
 * bleibt. Rein additiv - der Test belegt, dass ein bestehendes (Kamera-)Foto die Migration
 * uebersteht und automatisch `false` bekommt.
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
                    "VALUES (1, 1, 'MESSAUFBAU', '/data/foto1.jpg', 1500, NULL, NULL, 'abc123')"
            )
        }
    }

    private fun oeffneUeberProduktionspfad(context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, V20_DB_NAME)
            .addMigrations(*ALLE_MIGRATIONEN)
            .allowMainThreadQueries()
            .build()

    @Test
    fun bestehendesFotoUeberlebtDieMigrationMitFalseAlsDefault() {
        erzeugeV20Datenbank()
        val database = oeffneUeberProduktionspfad(ApplicationProvider.getApplicationContext())

        runBlocking {
            val fotos = database.dokumentationsFotoDao().fuerSession(1)
            assertEquals(1, fotos.size)
            assertFalse(fotos.single().nachtraeglichHinzugefuegt)
        }
        database.close()
    }

    @Test
    fun neuesGalerieFotoSpeichertDasFlagAlsTrue() {
        erzeugeV20Datenbank()
        val database = oeffneUeberProduktionspfad(ApplicationProvider.getApplicationContext())

        runBlocking {
            val neueId = database.dokumentationsFotoDao().insert(
                DokumentationsFotoEntity(
                    sessionId = 1,
                    kategorie = "SONSTIGES",
                    dateiPfad = "/data/foto2.jpg",
                    aufgenommenAm = 2000,
                    nachtraeglichHinzugefuegt = true,
                )
            )
            val geladen = database.dokumentationsFotoDao().byId(neueId)
            assertEquals(true, geladen?.nachtraeglichHinzugefuegt)
        }
        database.close()
    }
}
