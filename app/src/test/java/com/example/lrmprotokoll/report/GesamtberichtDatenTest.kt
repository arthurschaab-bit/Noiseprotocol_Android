package com.example.lrmprotokoll.report

import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.data.MeasurementEntity
import com.example.lrmprotokoll.data.SessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * [ermittleGesamtbericht] rechnet selbst nichts aus - siehe dessen KDoc: jeder Tag ist ein
 * gewoehnlicher Aufruf von [ermittlePeriodenBericht] (dort bereits getestet, siehe
 * [PeriodenBerichtDatenTest]). Hier wird nur die neue Tagesaufteilung selbst geprueft: dass ein
 * Zeitraum korrekt in Kalendertage geschnitten wird, auch an dessen Raendern.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GesamtberichtDatenTest {

    private val db
        get() = ApplicationProvider.getApplicationContext<LaermprotokollApp>().container.database

    private val zone = ZoneId.of("Europe/Berlin")

    @Test
    fun teiltEinenZweiTageZeitraumInZweiKalendertageAuf() = runBlocking {
        val tag1Beginn = ZonedDateTime.of(2026, 3, 10, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        val von = tag1Beginn + 20 * 3_600_000L // 10.03. 20:00 Uhr
        val bis = tag1Beginn + 34 * 3_600_000L // 11.03. 10:00 Uhr

        val bericht = ermittleGesamtbericht(db, von, bis, zone)

        assertEquals(2, bericht.tage.size)
        val ersterTag = bericht.tage[0]
        val zweiterTag = bericht.tage[1]
        assertEquals("Erster Tag beginnt am Zeitraumanfang, nicht an Mitternacht", von, ersterTag.von)
        assertEquals(tag1Beginn + 24 * 3_600_000L, ersterTag.bis)
        assertEquals(tag1Beginn + 24 * 3_600_000L, zweiterTag.von)
        assertEquals("Letzter Tag endet am Zeitraumende, nicht an Mitternacht", bis, zweiterTag.bis)
    }

    @Test
    fun zeitraumInnerhalbEinesEinzigenTagesErgibtGenauEinenTag() = runBlocking {
        val tagBeginn = ZonedDateTime.of(2026, 4, 5, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        val von = tagBeginn + 8 * 3_600_000L
        val bis = tagBeginn + 9 * 3_600_000L

        val bericht = ermittleGesamtbericht(db, von, bis, zone)

        assertEquals(1, bericht.tage.size)
        assertEquals(von, bericht.tage.single().von)
        assertEquals(bis, bericht.tage.single().bis)
    }

    @Test
    fun jederTagEnthaeltNurSeineEigenenMesswerte() = runBlocking {
        val tag1Beginn = ZonedDateTime.of(2026, 5, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        val von = tag1Beginn
        val bis = tag1Beginn + 48 * 3_600_000L

        val sessionId = db.sessionDao().insert(
            SessionEntity(
                startedAt = von, endedAt = bis,
                deviceAddress = "GesamtberichtTest", deviceName = "Geraet", weighting = null, timeWeighting = null,
            )
        )
        db.measurementDao().insertAll(
            listOf(
                MeasurementEntity(sessionId = sessionId, timestamp = tag1Beginn + 3_600_000L, levelDb = 40.0, weighting = null, flags = 0),
                MeasurementEntity(sessionId = sessionId, timestamp = tag1Beginn + 30 * 3_600_000L, levelDb = 80.0, weighting = null, flags = 0),
            )
        )

        val bericht = ermittleGesamtbericht(db, von, bis, zone)

        assertEquals(2, bericht.tage.size)
        assertEquals(1, bericht.tage[0].bericht.kennwerte.sampleCount)
        assertEquals(40.0, bericht.tage[0].bericht.kennwerte.maxDb!!, 0.01)
        assertEquals(1, bericht.tage[1].bericht.kennwerte.sampleCount)
        assertEquals(80.0, bericht.tage[1].bericht.kennwerte.maxDb!!, 0.01)
        assertTrue("Gesamtbericht muss beide Messwerte zusammenfassen", bericht.gesamt.kennwerte.sampleCount >= 2)
    }
}
