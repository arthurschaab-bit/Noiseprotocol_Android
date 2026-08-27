package com.example.lrmprotokoll.report

import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.data.ConnectionEventEntity
import com.example.lrmprotokoll.data.ConnectionEventType
import com.example.lrmprotokoll.data.MeasurementEntity
import com.example.lrmprotokoll.data.NoiseRecord
import com.example.lrmprotokoll.data.SessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PROMPT_M10_FUNKTIONEN.md F12: [ermittlePeriodenBericht] fasst mehrere Sessions zu einem
 * Zeitraumbericht zusammen. Die eigentliche Zahlen-Aggregation (Chart-Downsampling, Kennwerte)
 * laeuft ueber bereits anderswo getestete, session-neutrale Bausteine
 * ([com.example.lrmprotokoll.messreihe.downsampleMesswerteFuerChart],
 * [com.example.lrmprotokoll.messreihe.AkustischeKennwerte.berechne]) - hier wird nur die neue
 * Verdrahtung geprueft: dass Sessions/Messwerte/Ereignisse zeitraumuebergreifend zusammengefuehrt
 * werden, und dass Ausfallbaender auf den angefragten Zeitraum zurechtgeschnitten werden, auch wenn
 * die zugrundeliegende Session frueher beginnt oder spaeter endet als der Zeitraum.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PeriodenBerichtDatenTest {

    private val db
        get() = ApplicationProvider.getApplicationContext<LaermprotokollApp>().container.database

    @Test
    fun fasstMesswerteUndSessionsUeberDenZeitraumZusammen() = runBlocking {
        val basis = 3_000_000_000_000L
        val von = basis
        val bis = basis + 100_000

        val sessionAId = db.sessionDao().insert(
            SessionEntity(
                startedAt = von + 1_000, endedAt = von + 2_000,
                deviceAddress = "AA", deviceName = "Geraet A", weighting = null, timeWeighting = null,
            )
        )
        val sessionBId = db.sessionDao().insert(
            SessionEntity(
                startedAt = von + 5_000, endedAt = von + 6_000,
                deviceAddress = "BB", deviceName = "Geraet B", weighting = null, timeWeighting = null,
            )
        )
        db.measurementDao().insertAll(
            listOf(
                MeasurementEntity(sessionId = sessionAId, timestamp = von + 1_500, levelDb = 60.0, weighting = null, flags = 0),
                MeasurementEntity(sessionId = sessionBId, timestamp = von + 5_500, levelDb = 70.0, weighting = null, flags = 0),
            )
        )

        val bericht = ermittlePeriodenBericht(db, von, bis)

        // >= statt == 2: die Datenbank ist nicht in-memory-isoliert je Testmethode (siehe
        // MeasurementDaoTest) - eine andere, noch offene Session (endedAt = null) aus
        // SessionDaoTest ueberlappt mit JEDEM spaeteren Zeitraum, dessen `bis` nach ihrem Beginn
        // liegt, und zaehlt hier legitim mit. sampleCount/maxDb/minDb bleiben exakt pruefbar, weil
        // Messwerte nur ueber den engen, eindeutigen `basis`-Zeitraum gefiltert werden.
        assertTrue(bericht.sessionCount >= 2)
        assertEquals(2, bericht.kennwerte.sampleCount)
        assertEquals(70.0, bericht.kennwerte.maxDb!!, 0.01)
        assertEquals(60.0, bericht.kennwerte.minDb!!, 0.01)
    }

    @Test
    fun schneidetAusfallbaenderAufDenAngefragtenZeitraumZu() = runBlocking {
        val basis = 3_100_000_000_000L
        val von = basis
        val bis = basis + 50_000

        // Session beginnt VOR dem Zeitraum und laeuft noch (endedAt = null); der Ausfall beginnt
        // ebenfalls davor und endet innerhalb des Zeitraums - das Band muss vorne auf `von`
        // gekappt werden, nicht bei seinem tatsaechlichen (fruehereren) Beginn stehen bleiben.
        val sessionId = db.sessionDao().insert(
            SessionEntity(
                startedAt = von - 20_000, endedAt = null,
                deviceAddress = "CC", deviceName = "Geraet C", weighting = null, timeWeighting = null,
            )
        )
        db.connectionEventDao().insert(
            ConnectionEventEntity(sessionId = sessionId, at = von - 10_000, type = ConnectionEventType.DISCONNECTED, reason = null)
        )
        db.connectionEventDao().insert(
            ConnectionEventEntity(sessionId = sessionId, at = von + 10_000, type = ConnectionEventType.RECOVERED, reason = null)
        )

        val bericht = ermittlePeriodenBericht(db, von, bis)

        assertEquals(1, bericht.ausfallbaender.size)
        val band = bericht.ausfallbaender.single()
        assertEquals("Beginn muss auf den Zeitraumanfang gekappt werden", von, band.von)
        assertEquals(von + 10_000, band.bis)
    }

    @Test
    fun ignoriertSessionOhneUeberlappungMitDemZeitraum() = runBlocking {
        val basis = 3_150_000_000_000L
        val von = basis
        val bis = basis + 10_000

        // Session UND ihr Ausfall liegen vollstaendig vor dem Zeitraum.
        val sessionId = db.sessionDao().insert(
            SessionEntity(
                startedAt = von - 30_000, endedAt = von - 20_000,
                deviceAddress = "DD", deviceName = "Geraet D", weighting = null, timeWeighting = null,
            )
        )
        db.connectionEventDao().insert(
            ConnectionEventEntity(sessionId = sessionId, at = von - 25_000, type = ConnectionEventType.DISCONNECTED, reason = null)
        )
        db.connectionEventDao().insert(
            ConnectionEventEntity(sessionId = sessionId, at = von - 21_000, type = ConnectionEventType.RECOVERED, reason = null)
        )

        val bericht = ermittlePeriodenBericht(db, von, bis)

        // Nicht sessionCount == 0 (die Datenbank ist nicht in-memory-isoliert je Testmethode,
        // siehe Kommentar in fasstMesswerteUndSessionsUeberDenZeitraumZusammen) - stattdessen
        // gezielt pruefen, dass GENAU UNSERE Session (eindeutiger deviceAddress-Marker) nicht im
        // Ergebnis auftaucht. ausfallbaender bleibt exakt pruefbar: Verbindungsereignisse sind
        // hier ausschliesslich an unsere eigene, nicht ueberlappende Session gebunden.
        assertTrue(db.sessionDao().zwischen(von, bis).none { it.deviceAddress == "DD" })
        assertTrue(bericht.ausfallbaender.isEmpty())
    }

    @Test
    fun filtertEreignisseAufDenZeitraum() = runBlocking {
        val basis = 3_200_000_000_000L
        val von = basis
        val bis = basis + 10_000

        db.noiseDao().insert(
            NoiseRecord(timestamp = von + 1_000, amplitude = 0.0, dbValue = 55.0, filePath = "", label = "Innerhalb")
        )
        db.noiseDao().insert(
            NoiseRecord(timestamp = von - 5_000, amplitude = 0.0, dbValue = 55.0, filePath = "", label = "Ausserhalb")
        )

        val bericht = ermittlePeriodenBericht(db, von, bis)

        assertTrue(bericht.events.any { it.label == "Innerhalb" })
        assertTrue(bericht.events.none { it.label == "Ausserhalb" })
    }
}
