package com.example.lrmprotokoll.report

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.data.AppDatabase
import com.example.lrmprotokoll.data.MeasurementEntity
import com.example.lrmprotokoll.data.SessionEntity
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S-4: der Bericht folgt der Tagesauswahl, nicht einem zusammenhaengenden Fenster.
 *
 * Der zweite Test ist der eigentliche Grund fuer [ermittlePeriodenBerichtFuerTage]: waere die
 * Auswahl nur ein `von`/`bis`, laege der abgewaehlte Tag still im PDF.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BerichtFuerMesstageTest {
    private val zone = ZoneId.of("Europe/Berlin")

    private fun millis(lokal: String): Long =
        LocalDateTime.parse(lokal).atZone(zone).toInstant().toEpochMilli()

    private fun db(context: Context) =
        Room
            .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    /** Legt an drei Tagen je eine Session mit je zwei Messwerten an. */
    private suspend fun fuelle(datenbank: AppDatabase) {
        listOf(
            "2026-09-25" to 50.0,
            "2026-09-27" to 70.0,
            "2026-09-29" to 60.0,
        ).forEach { (tag, pegel) ->
            val id = datenbank.sessionDao().insert(
                SessionEntity(
                    startedAt = millis("${tag}T10:00"),
                    endedAt = millis("${tag}T11:00"),
                    deviceAddress = "AA:BB",
                    deviceName = "PCE-323",
                    weighting = "A",
                    timeWeighting = "FAST",
                ),
            )
            datenbank.measurementDao().insertAll(
                listOf(
                    MeasurementEntity(
                        sessionId = id,
                        timestamp = millis("${tag}T10:10"),
                        levelDb = pegel,
                        weighting = "A",
                        timeWeighting = "FAST",
                        flags = 0,
                    ),
                    MeasurementEntity(
                        sessionId = id,
                        timestamp = millis("${tag}T10:20"),
                        levelDb = pegel,
                        weighting = "A",
                        timeWeighting = "FAST",
                        flags = 0,
                    ),
                ),
            )
        }
    }

    private val zeitraum = BerichtZeitraum(LocalDate.parse("2026-09-25"), LocalDate.parse("2026-09-29"))

    @Test
    fun alleTageGewaehltLiefertDasselbeWieDasFenster() = runBlocking {
        val datenbank = db(ApplicationProvider.getApplicationContext())
        fuelle(datenbank)
        val tage = ladeMesstage(datenbank, zeitraum, zone = zone, jetzt = millis("2026-09-30T00:00"))
        assertEquals("Drei Messtage erwartet", 3, tage.size)

        val ueberTage = ermittlePeriodenBerichtFuerTage(datenbank, tage)
        val ueberFenster = ermittlePeriodenBericht(
            datenbank,
            millis("2026-09-25T00:00"),
            millis("2026-09-30T00:00"),
        )

        assertEquals(ueberFenster.sessionCount, ueberTage.sessionCount)
        assertEquals(ueberFenster.kennwerte.sampleCount, ueberTage.kennwerte.sampleCount)
        assertEquals(ueberFenster.kennwerte.leqDb, ueberTage.kennwerte.leqDb)
        assertEquals(ueberFenster.kennwerte.maxDb, ueberTage.kennwerte.maxDb)
        datenbank.close()
    }

    @Test
    fun abgewaehlterTagInDerMitteFehltImBericht() = runBlocking {
        val datenbank = db(ApplicationProvider.getApplicationContext())
        fuelle(datenbank)
        val tage = ladeMesstage(datenbank, zeitraum, zone = zone, jetzt = millis("2026-09-30T00:00"))
        val ohneMitte = tage.filter { it.datum != LocalDate.parse("2026-09-27") }
        assertEquals(2, ohneMitte.size)

        val bericht = ermittlePeriodenBerichtFuerTage(datenbank, ohneMitte)

        assertEquals("Nur die zwei gewaehlten Sessions", 2, bericht.sessionCount)
        assertEquals("Vier Messwerte statt sechs", 4, bericht.kennwerte.sampleCount)
        assertEquals(
            "Der laute Tag ist abgewaehlt, also darf sein Maximum nicht auftauchen",
            60.0,
            bericht.kennwerte.maxDb!!,
            0.001,
        )

        val mitMitte = ermittlePeriodenBericht(datenbank, bericht.von, bericht.bis)
        assertNotEquals(
            "Gegenprobe: dasselbe Fenster ohne Tagesauswahl enthaelt den abgewaehlten Tag",
            bericht.kennwerte.sampleCount,
            mitMitte.kennwerte.sampleCount,
        )
        assertTrue("Das Fenster enthaelt alle sechs Messwerte", mitMitte.kennwerte.sampleCount == 6)
        datenbank.close()
    }

    @Test
    fun gesamtberichtBekommtTagesseitenNurFuerGewaehlteTage() = runBlocking {
        val datenbank = db(ApplicationProvider.getApplicationContext())
        fuelle(datenbank)
        val tage = ladeMesstage(datenbank, zeitraum, zone = zone, jetzt = millis("2026-09-30T00:00"))
        val ohneMitte = tage.filter { it.datum != LocalDate.parse("2026-09-27") }

        val gesamt = ermittleGesamtberichtFuerTage(datenbank, ohneMitte)

        assertEquals(2, gesamt.tage.size)
        assertEquals(
            "Die Tagesseiten stehen chronologisch",
            listOf(millis("2026-09-25T00:00"), millis("2026-09-29T00:00")),
            gesamt.tage.map { it.von },
        )
        datenbank.close()
    }
}
