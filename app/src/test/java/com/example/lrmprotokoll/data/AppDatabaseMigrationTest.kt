package com.example.lrmprotokoll.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val TEST_DB_NAME = "migration-test"

/**
 * B-2: fallbackToDestructiveMigration() wurde entfernt, weil er bei jeder Schemaaenderung
 * alle bisherigen Messdaten geloescht haette. Dieser Test beweist, dass eine bestehende
 * Version-6-Datenbank mit echten Datensaetzen unveraendert erhalten bleibt, wenn sie ueber
 * den produktiven Code-Pfad (AppDatabase, ohne destruktiven Fallback) geoeffnet wird.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun bestandsdatenUeberlebenDasOeffnenOhneDestruktivenFallback() {
        // Simuliert eine v6-Datenbank, wie sie auf einem echten Geraet nach laengerem
        // Gebrauch vorliegen wuerde, inkl. NULL-Werten in optionalen Spalten.
        helper.createDatabase(TEST_DB_NAME, 6).use { db ->
            db.execSQL(
                "INSERT INTO noise_records (id, timestamp, amplitude, dbValue, filePath, label, detectedLabel) " +
                    "VALUES (1, 1700000000000, 0.42, 63.5, '/data/rec1.wav', 'Bohren', 'Bohren')"
            )
            db.execSQL(
                "INSERT INTO noise_records (id, timestamp, amplitude, dbValue, filePath, label, detectedLabel) " +
                    "VALUES (2, 1700000005000, 0.11, 51.2, '/data/rec2.wav', NULL, NULL)"
            )
            db.execSQL(
                "INSERT INTO reference_sounds (id, name, pattern) VALUES (1, 'Presslufthammer', 'Jackhammer,Hammer')"
            )
        }

        // Oeffnet dieselbe Datenbankdatei ueber den echten Produktionscode-Pfad.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB_NAME)
            .allowMainThreadQueries()
            .build()

        database.query("SELECT id, timestamp, amplitude, dbValue, filePath, label, detectedLabel FROM noise_records ORDER BY id", null).use { cursor ->
            assertEquals(2, cursor.count)

            cursor.moveToPosition(0)
            assertEquals(1L, cursor.getLong(0))
            assertEquals(1700000000000L, cursor.getLong(1))
            assertEquals(0.42, cursor.getDouble(2), 0.0001)
            assertEquals(63.5, cursor.getDouble(3), 0.0001)
            assertEquals("/data/rec1.wav", cursor.getString(4))
            assertEquals("Bohren", cursor.getString(5))
            assertEquals("Bohren", cursor.getString(6))

            cursor.moveToPosition(1)
            assertEquals(2L, cursor.getLong(0))
            assertEquals("/data/rec2.wav", cursor.getString(4))
            assertNull(cursor.getString(5))
            assertNull(cursor.getString(6))
        }

        database.query("SELECT id, name, pattern FROM reference_sounds ORDER BY id", null).use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToPosition(0)
            assertEquals("Presslufthammer", cursor.getString(1))
            assertEquals("Jackhammer,Hammer", cursor.getString(2))
        }

        database.close()
    }
}
