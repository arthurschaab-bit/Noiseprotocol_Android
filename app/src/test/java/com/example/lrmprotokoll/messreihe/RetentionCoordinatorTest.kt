package com.example.lrmprotokoll.messreihe

import com.example.lrmprotokoll.alert.TestUhr
import com.example.lrmprotokoll.data.MeasurementDao
import com.example.lrmprotokoll.data.MeasurementEntity
import com.example.lrmprotokoll.data.MinuteAggregateDao
import com.example.lrmprotokoll.data.MinuteAggregateEntity
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric, weil verdichte() echtes android.util.Log durchlaeuft (Log.i beim Erfolg). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetentionCoordinatorTest {

    private class FakeMeasurementDao : MeasurementDao {
        val zeilen = mutableListOf<MeasurementEntity>()
        override suspend fun insertAll(messwerte: List<MeasurementEntity>) { zeilen += messwerte }
        override suspend fun fuerSession(sessionId: Long) = zeilen.filter { it.sessionId == sessionId }
        override fun fuerSessionFlow(sessionId: Long) = throw NotImplementedError("im Test nicht benoetigt")
        override fun fuerSessionAbFlow(sessionId: Long, ab: Long) = throw NotImplementedError("im Test nicht benoetigt")
        override suspend fun zwischen(von: Long, bis: Long) = zeilen.filter { it.timestamp in von until bis }
        override suspend fun aelterAls(grenze: Long) = zeilen.filter { it.timestamp < grenze }
        override suspend fun loescheAelterAls(grenze: Long) { zeilen.removeAll { it.timestamp < grenze } }
        override suspend fun anzahl(): Int = zeilen.size
    }

    private class FakeMinuteAggregateDao : MinuteAggregateDao {
        val geschrieben = mutableListOf<MinuteAggregateEntity>()
        override suspend fun insertAll(aggregate: List<MinuteAggregateEntity>) { geschrieben += aggregate }
        override suspend fun fuerSession(sessionId: Long) = geschrieben.filter { it.sessionId == sessionId }
        override fun fuerSessionFlow(sessionId: Long) = throw NotImplementedError("im Test nicht benoetigt")
        override suspend fun zwischen(von: Long, bis: Long) = geschrieben.filter { it.minuteStart in von until bis }
    }

    private val measurementDao = FakeMeasurementDao()
    private val minuteAggregateDao = FakeMinuteAggregateDao()
    private val uhr = TestUhr(Instant.parse("2026-08-19T12:00:00Z"))

    private fun messwert(alterTage: Long, sekundeInnerhalbDerMinute: Long, level: Double, sessionId: Long = 1) =
        MeasurementEntity(
            sessionId = sessionId,
            timestamp = uhr.now().minus(Duration.ofDays(alterTage)).plusSeconds(sekundeInnerhalbDerMinute).toEpochMilli(),
            levelDb = level, weighting = null, flags = 0,
        )

    private fun koordinator() = RetentionCoordinator(measurementDao, minuteAggregateDao, uhr, aufbewahrungstage = 90)

    @Test
    fun werteInnerhalbDerAufbewahrungsfristBleibenUnangetastet() = runTest {
        measurementDao.zeilen += messwert(alterTage = 10, sekundeInnerhalbDerMinute = 0, level = 55.0)

        koordinator().verdichte()

        assertEquals(1, measurementDao.zeilen.size)
        assertTrue(minuteAggregateDao.geschrieben.isEmpty())
    }

    @Test
    fun aeltereWerteWerdenZuMinutenaggregatenVerdichtetUndGeloescht() = runTest {
        measurementDao.zeilen += messwert(alterTage = 91, sekundeInnerhalbDerMinute = 0, level = 50.0)
        measurementDao.zeilen += messwert(alterTage = 91, sekundeInnerhalbDerMinute = 30, level = 60.0)

        val anzahl = koordinator().verdichte()

        assertEquals(1, anzahl)
        assertTrue("Rohwerte muessen nach der Verdichtung weg sein", measurementDao.zeilen.isEmpty())
        val aggregat = minuteAggregateDao.geschrieben.single()
        assertEquals(2, aggregat.sampleCount)
        assertEquals(60.0, aggregat.maxDb, 0.0001)
        assertEquals(50.0, aggregat.minDb, 0.0001)
    }

    @Test
    fun leqImAggregatIstEnergetischNichtArithmetisch() = runTest {
        measurementDao.zeilen += messwert(alterTage = 91, sekundeInnerhalbDerMinute = 0, level = 60.0)
        measurementDao.zeilen += messwert(alterTage = 91, sekundeInnerhalbDerMinute = 1, level = 70.0)

        koordinator().verdichte()

        assertEquals(67.4036, minuteAggregateDao.geschrieben.single().leqDb, 0.001)
    }

    @Test
    fun getrennteMinutenErzeugenGetrennteAggregate() = runTest {
        measurementDao.zeilen += messwert(alterTage = 91, sekundeInnerhalbDerMinute = 0, level = 50.0)
        // Zweite Minute: 90 Sekunden statt 30 - landet in einem anderen 60s-Fenster.
        measurementDao.zeilen += MeasurementEntity(
            sessionId = 1,
            timestamp = uhr.now().minus(Duration.ofDays(91)).plusSeconds(90).toEpochMilli(),
            levelDb = 60.0, weighting = null, flags = 0,
        )

        koordinator().verdichte()

        assertEquals(2, minuteAggregateDao.geschrieben.size)
    }

    @Test
    fun getrennteSessionsWerdenNichtVermischt() = runTest {
        measurementDao.zeilen += messwert(alterTage = 91, sekundeInnerhalbDerMinute = 0, level = 50.0, sessionId = 1)
        measurementDao.zeilen += messwert(alterTage = 91, sekundeInnerhalbDerMinute = 0, level = 90.0, sessionId = 2)

        koordinator().verdichte()

        assertEquals(2, minuteAggregateDao.geschrieben.size)
        assertTrue(minuteAggregateDao.geschrieben.none { it.sampleCount != 1 })
    }

    @Test
    fun einheitlicheBewertungBleibtImAggregatErhalten() = runTest {
        val basis = messwert(alterTage = 91, sekundeInnerhalbDerMinute = 0, level = 50.0)
        measurementDao.zeilen += basis.copy(weighting = "A")
        measurementDao.zeilen += basis.copy(timestamp = basis.timestamp + 1000, weighting = "A")

        koordinator().verdichte()

        assertEquals("A", minuteAggregateDao.geschrieben.single().weighting)
    }

    @Test
    fun gemischteBewertungInnerhalbEinerMinuteWirdNichtAlsEinzelwertVorgetaeuscht() = runTest {
        val basis = messwert(alterTage = 91, sekundeInnerhalbDerMinute = 0, level = 50.0)
        measurementDao.zeilen += basis.copy(weighting = "A")
        measurementDao.zeilen += basis.copy(timestamp = basis.timestamp + 1000, weighting = "C")

        koordinator().verdichte()

        assertNull(
            "Ein gemischtes A/C in derselben Minute darf nicht als ein einzelner Wert erscheinen",
            minuteAggregateDao.geschrieben.single().weighting,
        )
    }

    @Test
    fun einheitlicheZeitbewertungUndBereichBleibenImAggregatErhalten() = runTest {
        val basis = messwert(alterTage = 91, sekundeInnerhalbDerMinute = 0, level = 50.0)
        measurementDao.zeilen += basis.copy(timeWeighting = "FAST", range = "RANGE_30_130")
        measurementDao.zeilen += basis.copy(
            timestamp = basis.timestamp + 1000, timeWeighting = "FAST", range = "RANGE_30_130",
        )

        koordinator().verdichte()

        val aggregat = minuteAggregateDao.geschrieben.single()
        assertEquals("FAST", aggregat.timeWeighting)
        assertEquals("RANGE_30_130", aggregat.range)
    }

    @Test
    fun gemischteZeitbewertungUndBereichInnerhalbEinerMinuteWerdenNichtAlsEinzelwertVorgetaeuscht() = runTest {
        val basis = messwert(alterTage = 91, sekundeInnerhalbDerMinute = 0, level = 50.0)
        measurementDao.zeilen += basis.copy(timeWeighting = "FAST", range = "RANGE_30_130")
        measurementDao.zeilen += basis.copy(
            timestamp = basis.timestamp + 1000, timeWeighting = "SLOW", range = "RANGE_50_100",
        )

        koordinator().verdichte()

        val aggregat = minuteAggregateDao.geschrieben.single()
        assertNull("Gemischte Zeitbewertung darf nicht als ein Wert erscheinen", aggregat.timeWeighting)
        assertNull("Gemischter Bereich darf nicht als ein Wert erscheinen", aggregat.range)
    }

    /**
     * Der Endzustand allein beweist die Reihenfolge nicht - bei einem Fake ohne simulierten
     * Abbruch sehen "erst schreiben, dann loeschen" und "erst loeschen, dann schreiben" am Ende
     * gleich aus. Diese Pruefung erzwingt einen Abbruch zwischen beiden Schritten (Prozess-Tod,
     * Speicherdruck) und stellt sicher, dass das Aggregat dann bereits geschrieben ist - sonst
     * waeren die Rohdaten weder als Rohwert (werden gleich geloescht) noch als Aggregat
     * wiederauffindbar.
     */
    @Test
    fun aggregateWerdenVorDemLoeschenGeschriebenDamitEinAbbruchKeineDatenVerliert() = runTest {
        measurementDao.zeilen += messwert(alterTage = 91, sekundeInnerhalbDerMinute = 0, level = 55.0)
        val messungDaoMitAbbruch = object : MeasurementDao by measurementDao {
            override suspend fun loescheAelterAls(grenze: Long) {
                throw RuntimeException("simulierter Abbruch zwischen Schreiben und Loeschen")
            }
        }
        val koordinatorMitAbbruch = RetentionCoordinator(
            messungDaoMitAbbruch, minuteAggregateDao, uhr, aufbewahrungstage = 90,
        )

        val ergebnis = runCatching { koordinatorMitAbbruch.verdichte() }

        assertTrue("Der simulierte Abbruch muss durchschlagen", ergebnis.isFailure)
        assertEquals(
            "Auch wenn das Loeschen scheitert, muss das Aggregat schon geschrieben sein",
            1, minuteAggregateDao.geschrieben.size,
        )
    }

    @Test
    fun ohneAeltereWerteWirdNichtsGeschrieben() = runTest {
        measurementDao.zeilen += messwert(alterTage = 5, sekundeInnerhalbDerMinute = 0, level = 55.0)

        val anzahl = koordinator().verdichte()

        assertEquals(0, anzahl)
        assertTrue(minuteAggregateDao.geschrieben.isEmpty())
    }
}
