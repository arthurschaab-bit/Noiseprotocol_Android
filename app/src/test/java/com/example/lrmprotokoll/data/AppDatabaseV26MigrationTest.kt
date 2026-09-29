package com.example.lrmprotokoll.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val V25_DB_NAME = "v25-to-v26-migration-test"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseV26MigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AppDatabase::class.java,
            emptyList(),
            FrameworkSQLiteOpenHelperFactory(),
        )

    @Test
    fun bestehendeSessionsWerdenOhneDatenverlustZuEigenenMessvorgaengen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbPath = context.getDatabasePath(V25_DB_NAME).path
        helper.createDatabase(dbPath, 25).use { db ->
            db.execSQL(
                "INSERT INTO sessions (id, startedAt, endedAt, deviceAddress, deviceName) VALUES " +
                    "(11, 1000, 2000, '', 'Smartphone-Mikrofon'), " +
                    "(12, 2001, 3000, 'PCE', 'PCE-323')",
            )
            db.execSQL(
                "INSERT INTO measurements (id, sessionId, timestamp, levelDb, weighting, flags) " +
                    "VALUES (31, 11, 1500, 55.0, 'A', 0)",
            )
            db.execSQL(
                "INSERT INTO stammdaten_verlauf (id, erstelltAm, geraetHersteller, geraetTyp, " +
                    "geraetGenauigkeitsklasse, geraetSeriennummer, geraetKalibrierung, messort, " +
                    "mikrofonposition, mikrofonhoehe, entfernungZurQuelle, innenAussen, " +
                    "fensterzustand, wetter, datenqualitaetHinweis) VALUES " +
                    "(41, 1500, '', '', '', '', '', 'Alter Messort', '', '', '', '', '', '', '')",
            )
        }

        val database =
            Room
                .databaseBuilder(context, AppDatabase::class.java, V25_DB_NAME)
                .addMigrations(*ALLE_MIGRATIONEN)
                .allowMainThreadQueries()
                .build()
        try {
            runBlocking {
                val mikrofon = database.sessionDao().byId(11)
                val messgeraet = database.sessionDao().byId(12)
                assertNotNull(mikrofon)
                assertNotNull(messgeraet)
                assertEquals(11L, mikrofon?.messvorgangId)
                assertEquals(12L, messgeraet?.messvorgangId)
                assertEquals(false, mikrofon?.photoPromptCompleted)
                assertEquals(false, messgeraet?.metadataPromptCompleted)
                assertEquals(2000L, mikrofon?.endedAt)
                assertEquals("PCE-323", messgeraet?.deviceName)

                val messwerte = database.measurementDao().fuerSession(11)
                assertEquals(1, messwerte.size)
                assertEquals(31L, messwerte.single().id)
                assertEquals(55.0, messwerte.single().levelDb, 0.0)
                val stammdaten = database.stammdatenVerlaufDao().fuerTag(1000, 2000).single()
                assertEquals(41L, stammdaten.id)
                assertEquals("Alter Messort", stammdaten.messort)
                assertEquals(null, stammdaten.messvorgangId)
            }
        } finally {
            database.close()
        }
    }
}
