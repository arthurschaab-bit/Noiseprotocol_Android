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

private const val V23_DB_NAME = "v23-to-v24-migration-test"

/**
 * Belegt, dass bestehende Berichtseinstellungen bei 23 -> 24 erhalten bleiben und die
 * konservativen Fenster- und Override-Defaults ohne Benutzeraktion sicher gesetzt werden.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseV24MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun bestehendeBerichtseinstellungenBleibenErhaltenUndNeueFelderErhaltenDefaults() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbPath = context.getDatabasePath(V23_DB_NAME).path
        helper.createDatabase(dbPath, 23).use { db ->
            db.execSQL(
                "INSERT INTO report_config (id, schaetzpegelTeilerfassungDb, schaetzpegelMessfensterAbbruchDb, " +
                    "tierSchwelleVollmessungProzent, tierSchwelleTeilerfassungProzent, gebietseinstufung, " +
                    "geraeteUnsicherheitDb) VALUES (1, 49.0, 54.0, 92.0, 72.0, 'WA', 1.7)"
            )
        }

        val database = Room.databaseBuilder(context, AppDatabase::class.java, V23_DB_NAME)
            .addMigrations(*ALLE_MIGRATIONEN)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            val config = database.reportConfigDao().get()
            assertEquals("WA", config?.gebietseinstufung)
            assertEquals(49.0, config?.schaetzpegelTeilerfassungDb ?: 0.0, 0.0001)
            assertEquals(54.0, config?.schaetzpegelMessfensterAbbruchDb ?: 0.0, 0.0001)
            assertEquals(92.0, config?.tierSchwelleVollmessungProzent ?: 0.0, 0.0001)
            assertEquals(72.0, config?.tierSchwelleTeilerfassungProzent ?: 0.0, 0.0001)
            assertEquals(1.7, config?.geraeteUnsicherheitDb ?: 0.0, 0.0001)
            assertEquals(15, config?.konservativFensterStartStunde)
            assertEquals(19, config?.konservativFensterEndeStunde)
            assertFalse(config?.erzwingeBerichtOhneBestaetigteBewertung ?: true)
        }
        database.close()
    }
}
