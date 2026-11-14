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

private const val V26_DB_NAME = "v26-to-v27-migration-test"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseV27MigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AppDatabase::class.java,
            emptyList(),
            FrameworkSQLiteOpenHelperFactory(),
        )

    @Test
    fun kalibrierTabellenKommenHinzuUndBestehendeDatenBleibenErhalten() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbPath = context.getDatabasePath(V26_DB_NAME).path
        helper.createDatabase(dbPath, 26).use { db ->
            db.execSQL(
                "INSERT INTO sessions (id, startedAt, endedAt, deviceAddress, deviceName, messvorgangId) VALUES " +
                    "(12, 2001, 3000, 'PCE', 'PCE-323', 12)",
            )
            db.execSQL(
                "INSERT INTO measurements (id, sessionId, timestamp, levelDb, weighting, flags) " +
                    "VALUES (31, 12, 2500, 61.5, 'A', 0)",
            )
        }

        val database =
            Room
                .databaseBuilder(context, AppDatabase::class.java, V26_DB_NAME)
                .addMigrations(*ALLE_MIGRATIONEN)
                .allowMainThreadQueries()
                .build()
        try {
            runBlocking {
                val messwerte = database.measurementDao().fuerSession(12)
                assertEquals(1, messwerte.size)
                assertEquals(61.5, messwerte.single().levelDb, 0.0)
                assertNotNull(database.sessionDao().byId(12))

                val dao = database.kalibrierphaseDao()
                val id = dao.insert(KalibrierphaseEntity(startedAt = 5_000))
                dao.insertMesswerte(listOf(KalibrierMesswertEntity(phaseId = id, timestamp = 5_100, levelDb = 93.8)))
                dao.update(
                    dao.byId(id)!!.copy(
                        endedAt = 9_000,
                        abweichungVorherDb = -0.2,
                        abweichungNachherDb = 0.0,
                        endeGrund = KalibrierEnde.MANUELL,
                    ),
                )
                val geladen = dao.byId(id)!!
                assertEquals(94.0, geladen.sollwertDb, 0.0)
                assertEquals(-0.2, geladen.abweichungVorherDb!!, 0.0)
                assertEquals(KalibrierEnde.MANUELL, geladen.endeGrund)
                database.openHelper.readableDatabase
                    .query("SELECT levelDb FROM kalibrier_messwerte WHERE phaseId = $id")
                    .use { cursor ->
                        assertEquals(1, cursor.count)
                        cursor.moveToFirst()
                        assertEquals(93.8, cursor.getDouble(0), 0.0)
                    }
                assertEquals(1, dao.zwischen(0, 10_000).size)
            }
        } finally {
            database.close()
        }
    }
}
