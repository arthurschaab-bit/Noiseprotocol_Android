package com.example.lrmprotokoll.data

import android.content.Context
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

private const val V6_DB_NAME = "v6-to-v7-migration-test"

/**
 * Migration 6 -> 7: die Tabelle `alerts` fuer M5.
 *
 * Die Migration ist rein additiv, deshalb ist die eigentliche Frage nicht "kommt die neue Tabelle
 * an", sondern "bleibt der Bestand unangetastet". Ein Nutzer, der die App seit Monaten laufen hat,
 * verliert an dieser Stelle entweder nichts - oder alles.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseV7MigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    private fun erzeugeV6Datenbank() {
        helper.createDatabase(V6_DB_NAME, 6).use { db ->
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
    }

    private fun oeffneUeberProduktionspfad(context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, V6_DB_NAME)
            .addMigrations(*ALLE_MIGRATIONEN)
            .allowMainThreadQueries()
            .build()

    @Test
    fun bestandAusV6UeberlebtDieMigrationAufV7() {
        erzeugeV6Datenbank()
        val database = oeffneUeberProduktionspfad(ApplicationProvider.getApplicationContext())

        database.query(
            "SELECT id, timestamp, amplitude, dbValue, filePath, label, detectedLabel " +
                "FROM noise_records ORDER BY id",
            null
        ).use { cursor ->
            assertEquals(2, cursor.count)

            cursor.moveToPosition(0)
            assertEquals(1L, cursor.getLong(0))
            assertEquals(1700000000000L, cursor.getLong(1))
            assertEquals(0.42, cursor.getDouble(2), 0.0001)
            assertEquals(63.5, cursor.getDouble(3), 0.0001)
            assertEquals("/data/rec1.wav", cursor.getString(4))
            assertEquals("Bohren", cursor.getString(5))

            cursor.moveToPosition(1)
            assertEquals(2L, cursor.getLong(0))
            assertEquals(51.2, cursor.getDouble(3), 0.0001)
            assertNull(cursor.getString(5))
            assertNull(cursor.getString(6))
        }

        database.query("SELECT name, pattern FROM reference_sounds", null).use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("Presslufthammer", cursor.getString(0))
            assertEquals("Jackhammer,Hammer", cursor.getString(1))
        }

        database.close()
    }

    @Test
    fun alertsTabelleExistiertNachDerMigrationUndIstBenutzbar() {
        erzeugeV6Datenbank()
        val database = oeffneUeberProduktionspfad(ApplicationProvider.getApplicationContext())

        database.query("SELECT COUNT(*) FROM alerts", null).use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }

        // Schreiben mit denselben Spalten, die der AlarmCoordinator benutzt: Weicht das per SQL
        // angelegte Schema von der Entity ab, faellt es hier auf und nicht erst zur Laufzeit.
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO alerts (outageSince, raisedAt, resolvedAt, reason, recipients, deliveryState, attempts, escalations) " +
                "VALUES (1700000000000, NULL, NULL, 'DISCONNECTED', '', 'PENDING', 0, 0)"
        )
        database.query(
            "SELECT sessionId, outageSince, raisedAt, reason, deliveryState FROM alerts",
            null
        ).use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertNull(cursor.getString(0))
            assertEquals(1700000000000L, cursor.getLong(1))
            assertNull(cursor.getString(2))
            assertEquals("DISCONNECTED", cursor.getString(3))
            assertEquals("PENDING", cursor.getString(4))
        }

        database.close()
    }

    /**
     * Gegenprobe gegen einen Identity-Hash-Fehler: Ein zweiter Start darf nicht erneut migrieren
     * wollen. Waere das per Hand geschriebene CREATE TABLE nicht deckungsgleich mit dem
     * exportierten 7.json, wuerde Room hier werfen statt die Datenbank einfach zu oeffnen.
     */
    @Test
    fun zweitesOeffnenNachDerMigrationLaeuftDurch() {
        erzeugeV6Datenbank()
        val context = ApplicationProvider.getApplicationContext<Context>()

        oeffneUeberProduktionspfad(context).apply {
            query("SELECT COUNT(*) FROM alerts", null).use { it.moveToFirst() }
            close()
        }

        oeffneUeberProduktionspfad(context).apply {
            query("SELECT COUNT(*) FROM noise_records", null).use { cursor ->
                cursor.moveToFirst()
                assertEquals(2, cursor.getInt(0))
            }
            close()
        }
    }
}
