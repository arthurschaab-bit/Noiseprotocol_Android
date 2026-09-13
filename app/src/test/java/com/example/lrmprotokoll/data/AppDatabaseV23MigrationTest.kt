package com.example.lrmprotokoll.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SimpleSQLiteQuery
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

private const val V22_DB_NAME = "v22-to-v23-migration-test"

/**
 * Migration 22 -> 23 (Bericht-Umbau Schritt 3, Owner-Klarstellung 13.09.2026): `report_config`
 * verliert `adresse`/`hardwareId` wieder (redundant zu `messort`/`geraetSeriennummer` in
 * [StammdatenVerlaufEntity]), behaelt aber alle anderen Werte. Der Test belegt, dass eine
 * bestehende v22-Zeile die Migration ueberlebt und die beiden Spalten danach tatsaechlich aus dem
 * Tabellenschema verschwunden sind (nicht nur ueber das aktuelle Entity unsichtbar).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseV23MigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    private fun erzeugeV22Datenbank() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbPath = context.getDatabasePath(V22_DB_NAME).path
        helper.createDatabase(dbPath, 22).use { db ->
            db.execSQL(
                "INSERT INTO report_config (id, schaetzpegelTeilerfassungDb, schaetzpegelMessfensterAbbruchDb, " +
                    "tierSchwelleVollmessungProzent, tierSchwelleTeilerfassungProzent, adresse, gebietseinstufung, " +
                    "hardwareId, geraeteUnsicherheitDb) VALUES (1, 50.0, 55.0, 90.0, 70.0, 'Musterstraße 1', 'WA', 'PCE-323-000123', 1.4)"
            )
        }
    }

    private fun oeffneUeberProduktionspfad(context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, V22_DB_NAME)
            .addMigrations(*ALLE_MIGRATIONEN)
            .allowMainThreadQueries()
            .build()

    @Test
    fun reportConfigVerliertAdresseUndHardwareIdBehaeltAberDieUebrigenWerte() {
        erzeugeV22Datenbank()
        val database = oeffneUeberProduktionspfad(ApplicationProvider.getApplicationContext())

        runBlocking {
            val geladen = database.reportConfigDao().get()
            assertEquals(50.0, geladen?.schaetzpegelTeilerfassungDb ?: 0.0, 0.0001)
            assertEquals(55.0, geladen?.schaetzpegelMessfensterAbbruchDb ?: 0.0, 0.0001)
            assertEquals(90.0, geladen?.tierSchwelleVollmessungProzent ?: 0.0, 0.0001)
            assertEquals(70.0, geladen?.tierSchwelleTeilerfassungProzent ?: 0.0, 0.0001)
            assertEquals("WA", geladen?.gebietseinstufung)
            assertEquals(1.4, geladen?.geraeteUnsicherheitDb ?: 0.0, 0.0001)
        }

        val spalten = mutableListOf<String>()
        database.query(SimpleSQLiteQuery("PRAGMA table_info(`report_config`)")).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) spalten.add(cursor.getString(nameIndex))
        }
        assertFalse("adresse-Spalte muss nach der Migration entfernt sein", spalten.contains("adresse"))
        assertFalse("hardwareId-Spalte muss nach der Migration entfernt sein", spalten.contains("hardwareId"))

        database.close()
    }
}
