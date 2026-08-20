package com.example.lrmprotokoll.messreihe

import com.example.lrmprotokoll.data.MeasurementEntity
import com.example.lrmprotokoll.data.MinuteAggregateEntity
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessreiheCsvTest {

    private val zone = ZoneId.of("Europe/Berlin")

    private fun messwert(
        zeitMs: Long, level: Double, weighting: String? = null, flags: Int = 0,
        timeWeighting: String? = null, range: String? = null,
    ) = MeasurementEntity(
        sessionId = 1, timestamp = zeitMs, levelDb = level, weighting = weighting, flags = flags,
        timeWeighting = timeWeighting, range = range,
    )

    private fun aggregat(
        minuteStart: Long, leq: Double, max: Double, min: Double, samples: Int, weighting: String? = null,
        timeWeighting: String? = null, range: String? = null,
    ) = MinuteAggregateEntity(
        sessionId = 1, minuteStart = minuteStart, leqDb = leq, maxDb = max, minDb = min,
        sampleCount = samples, weighting = weighting, timeWeighting = timeWeighting, range = range,
    )

    @Test
    fun kopfzeileNenntDbNichtDba() {
        val csv = MessreiheCsv.ausMesswerten(emptyList(), zone)
        assertTrue(csv.contains("Pegel_dB"))
        assertTrue(!csv.contains("_dBA"))
    }

    @Test
    fun beginntMitUtf8Bom() {
        val csv = MessreiheCsv.ausMesswerten(emptyList(), zone)
        assertEquals('﻿', csv.first())
    }

    @Test
    fun messwertZeileEnthaeltZeitPegelBewertungZeitbewertungBereichFlags() {
        // 1755324000000 ms = 2025-08-16T06:00:00Z = 08:00 MESZ
        val csv = MessreiheCsv.ausMesswerten(
            listOf(
                messwert(1755324000000, 52.3, weighting = "A", flags = 5, timeWeighting = "FAST", range = "RANGE_30_130"),
            ),
            zone,
        )
        assertTrue(csv.contains("2025-08-16T08:00:00+02:00;52,3;A;FAST;RANGE_30_130;5"))
    }

    @Test
    fun unbekannteEinstellungenBleibenLeerstringNichtNullText() {
        val csv = MessreiheCsv.ausMesswerten(listOf(messwert(0, 52.3, weighting = null)), zone)
        assertTrue(csv.contains(";52,3;;;;0"))
        assertTrue(!csv.contains("null"))
    }

    @Test
    fun dezimaltrennzeichenIstKommaNichtPunkt() {
        val csv = MessreiheCsv.ausMesswerten(listOf(messwert(0, 52.3)), zone)
        assertTrue(!csv.contains("52.3"))
        assertTrue(csv.contains("52,3"))
    }

    @Test
    fun messwerteWerdenVorDerAusgabeNachZeitSortiert() {
        val csv = MessreiheCsv.ausMesswerten(
            listOf(messwert(2000, 70.0), messwert(0, 60.0), messwert(1000, 65.0)), zone,
        )
        val zeilen = csv.split("\r\n").drop(1).filter { it.isNotBlank() }
        assertEquals(listOf("60,0", "65,0", "70,0"), zeilen.map { it.split(";")[1] })
    }

    @Test
    fun zeilenEndenAufCrlf() {
        val csv = MessreiheCsv.ausMesswerten(listOf(messwert(0, 52.3)), zone)
        assertTrue(csv.endsWith("\r\n"))
    }

    @Test
    fun aggregatKopfzeileNenntDbNichtDba() {
        val csv = MessreiheCsv.ausAggregaten(emptyList(), zone)
        assertTrue(csv.contains("LAeq_dB"))
        assertTrue(!csv.contains("_dBA"))
    }

    @Test
    fun aggregatZeileEnthaeltAlleSpalten() {
        val csv = MessreiheCsv.ausAggregaten(
            listOf(
                aggregat(
                    1755324000000, leq = 55.5, max = 60.0, min = 50.0, samples = 118,
                    weighting = "A", timeWeighting = "SLOW", range = "RANGE_50_100",
                ),
            ),
            zone,
        )
        assertTrue(csv.contains("2025-08-16T08:00:00+02:00;55,5;60,0;50,0;118;A;SLOW;RANGE_50_100"))
    }

    @Test
    fun aggregateWerdenVorDerAusgabeNachMinuteSortiert() {
        val csv = MessreiheCsv.ausAggregaten(
            listOf(
                aggregat(120_000, leq = 70.0, max = 70.0, min = 70.0, samples = 1),
                aggregat(0, leq = 60.0, max = 60.0, min = 60.0, samples = 1),
                aggregat(60_000, leq = 65.0, max = 65.0, min = 65.0, samples = 1),
            ),
            zone,
        )
        val zeilen = csv.split("\r\n").drop(1).filter { it.isNotBlank() }
        assertEquals(listOf("60,0", "65,0", "70,0"), zeilen.map { it.split(";")[1] })
    }
}
