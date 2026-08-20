package com.example.lrmprotokoll.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit-Test für die [RobolectricOpenHelperFactory].
 *
 * Verifiziert, dass absolute und relative Datenbankpfade sowie In-Memory-Konfigurationen
 * unter Robolectric plattformunabhängig (Windows und Linux) korrekt an den SQLite-Treiber
 * übergeben werden und keine Pfadvergleiche in Room fehlschlagen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RobolectricOpenHelperFactoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val factory = RobolectricOpenHelperFactory()

    private val testCallback = object : SupportSQLiteOpenHelper.Callback(1) {
        override fun onCreate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE test_table (id INTEGER PRIMARY KEY, value TEXT)")
        }

        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    @Test
    fun erzeugtGueltigenHelperFuerBenannteDatenbank() {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("test_db_named.db")
            .callback(testCallback)
            .build()

        val helper = factory.create(config)
        assertNotNull(helper)
        val expectedPath = context.getDatabasePath("test_db_named.db").absolutePath
        assertEquals(expectedPath, helper.databaseName)

        val db = helper.writableDatabase
        assertTrue(db.isOpen)
        db.execSQL("INSERT INTO test_table (id, value) VALUES (1, 'Hello')")

        val cursor = db.query("SELECT value FROM test_table WHERE id = 1")
        assertTrue(cursor.moveToFirst())
        assertEquals("Hello", cursor.getString(0))
        cursor.close()
        db.close()
    }

    @Test
    fun erzeugtGueltigenHelperFuerInMemoryDatenbank() {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null)
            .callback(testCallback)
            .build()

        val helper = factory.create(config)
        assertNotNull(helper)
        assertNull(helper.databaseName)

        val db = helper.writableDatabase
        assertTrue(db.isOpen)
        db.close()
    }
}
