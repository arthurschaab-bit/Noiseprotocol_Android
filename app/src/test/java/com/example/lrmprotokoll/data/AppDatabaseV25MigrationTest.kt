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

private const val V24_DB_NAME = "v24-to-v25-migration-test"

/** Alte Stammdaten bleiben zeitlich unverändert; Nachträge tragen einen separaten Messtag. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseV25MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun alterEintragBleibtErhaltenUndNachtragWirdDemMesstagZugeordnet() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbPath = context.getDatabasePath(V24_DB_NAME).path
        helper.createDatabase(dbPath, 24).use { db ->
            db.execSQL(
                "INSERT INTO stammdaten_verlauf (id, erstelltAm, geraetHersteller, geraetTyp, " +
                    "geraetGenauigkeitsklasse, geraetSeriennummer, geraetKalibrierung, messort, " +
                    "mikrofonposition, mikrofonhoehe, entfernungZurQuelle, innenAussen, " +
                    "fensterzustand, wetter, datenqualitaetHinweis) VALUES " +
                    "(1, 1700000000000, 'Hersteller', 'Typ', '1', 'SN1', 'Kalibriert', " +
                    "'Messort', 'Position', '1m', '5m', 'Außen', '', 'trocken', '')"
            )
        }

        val database = Room.databaseBuilder(context, AppDatabase::class.java, V24_DB_NAME)
            .addMigrations(*ALLE_MIGRATIONEN)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            val original = database.stammdatenVerlaufDao().zwischen(1_700_000_000_000, 1_700_000_000_001).single()
            assertNull(original.giltFuerTagStart)
            assertEquals("Messort", original.messort)

            val tagStart = 1_600_000_000_000L
            database.stammdatenVerlaufDao().insert(
                original.copy(id = 0, erstelltAm = 1_800_000_000_000L, giltFuerTagStart = tagStart)
            )
            val fuerAltenTag = database.stammdatenVerlaufDao().fuerTag(tagStart, tagStart + 86_400_000)
            assertEquals(1, fuerAltenTag.size)
            assertEquals(1_800_000_000_000L, fuerAltenTag.single().erstelltAm)
            assertEquals(tagStart, fuerAltenTag.single().giltFuerTagStart)
            assertTrue(database.stammdatenVerlaufDao().fuerTag(1_700_000_000_000, 1_700_000_000_001).contains(original))
            assertEquals(listOf(original), database.stammdatenVerlaufDao().letzte())
        }
        database.close()
    }
}
