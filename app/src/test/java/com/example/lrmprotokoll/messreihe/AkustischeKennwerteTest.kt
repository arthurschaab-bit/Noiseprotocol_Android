package com.example.lrmprotokoll.messreihe

import com.example.lrmprotokoll.data.MeasurementEntity
import com.example.lrmprotokoll.data.MinuteAggregateEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AkustischeKennwerteTest {

    private fun messwert(zeitMs: Long, level: Double) =
        MeasurementEntity(sessionId = 1, timestamp = zeitMs, levelDb = level, weighting = null, flags = 0)

    private fun aggregat(minuteStart: Long, leq: Double, max: Double, min: Double, samples: Int) =
        MinuteAggregateEntity(
            sessionId = 1, minuteStart = minuteStart, leqDb = leq, maxDb = max, minDb = min,
            sampleCount = samples, weighting = null,
        )

    @Test
    fun leereListeLiefertNullwerte() {
        val k = AkustischeKennwerte.berechne(emptyList())
        assertNull(k.leqDb)
        assertNull(k.maxDb)
        assertEquals(0, k.sampleCount)
        assertEquals(0L, k.ueberschreitungsdauerMs)
    }

    @Test
    fun leqIstEnergetischerMittelwertNichtArithmetisch() {
        // 60 dB und 70 dB energetisch: 10*log10((10^6+10^7)/2) ~ 67,40 - nicht 65 (arithmetisch).
        val k = AkustischeKennwerte.berechne(listOf(messwert(0, 60.0), messwert(1, 70.0)))
        assertEquals(67.4036, k.leqDb!!, 0.001)
    }

    @Test
    fun maxUndMinStimmen() {
        val k = AkustischeKennwerte.berechne(listOf(messwert(0, 55.0), messwert(1, 80.0), messwert(2, 40.0)))
        assertEquals(80.0, k.maxDb!!, 0.0001)
        assertEquals(40.0, k.minDb!!, 0.0001)
    }

    @Test
    fun l50IstDerMedianBeiUngeraderAnzahl() {
        val k = AkustischeKennwerte.berechne(listOf(messwert(0, 50.0), messwert(1, 60.0), messwert(2, 70.0)))
        assertEquals(60.0, k.l50Db!!, 0.0001)
    }

    @Test
    fun l10IstDerHoheUndL90DerNiedrigeBereich() {
        // Zehn Werte von 10 bis 100 in 10er-Schritten, absichtlich unsortiert eingespeist.
        val werte = listOf(50.0, 10.0, 90.0, 30.0, 70.0, 20.0, 100.0, 40.0, 80.0, 60.0)
        val k = AkustischeKennwerte.berechne(werte.mapIndexed { i, v -> messwert(i.toLong(), v) })

        assertTrue(
            "L10 (nur 10% der Zeit ueberschritten) muss im oberen Bereich liegen, war ${k.l10Db}",
            k.l10Db!! >= 90.0,
        )
        assertTrue(
            "L90 (90% der Zeit ueberschritten) muss im unteren Bereich liegen, war ${k.l90Db}",
            k.l90Db!! <= 20.0,
        )
        assertTrue("L10 muss ueber L50 liegen", k.l10Db!! > k.l50Db!!)
        assertTrue("L50 muss ueber L90 liegen", k.l50Db!! > k.l90Db!!)
    }

    @Test
    fun einzelnerMesswertLiefertDenselbenWertUeberall() {
        val k = AkustischeKennwerte.berechne(listOf(messwert(0, 55.0)))
        assertEquals(55.0, k.leqDb!!, 0.0001)
        assertEquals(55.0, k.maxDb!!, 0.0001)
        assertEquals(55.0, k.minDb!!, 0.0001)
        assertEquals(55.0, k.l10Db!!, 0.0001)
        assertEquals(55.0, k.l50Db!!, 0.0001)
        assertEquals(55.0, k.l90Db!!, 0.0001)
    }

    @Test
    fun ueberschreitungsdauerSummiertNurZeitraeumeUeberDerSchwelle() {
        // 0s: 40dB (unter Schwelle), 1s: 70dB (ueber, 2s bis zum naechsten Wert),
        // 3s: 30dB (unter), 4s: 30dB (unter) - nur das Intervall [1s,3s) zaehlt: 2000 ms.
        val werte = listOf(
            messwert(0, 40.0), messwert(1_000, 70.0), messwert(3_000, 30.0), messwert(4_000, 30.0),
        )
        val k = AkustischeKennwerte.berechne(werte, ueberschreitungsSchwelleDb = 60.0)
        assertEquals(2_000L, k.ueberschreitungsdauerMs)
    }

    @Test
    fun ohneSchwelleIstDieUeberschreitungsdauerNull() {
        val k = AkustischeKennwerte.berechne(listOf(messwert(0, 90.0), messwert(1_000, 90.0)))
        assertEquals(0L, k.ueberschreitungsdauerMs)
    }

    @Test
    fun unsortierteEingabeWirdVorDerBerechnungNachZeitSortiert() {
        // Absichtlich in falscher Reihenfolge eingespeist - die Ueberschreitungsdauer haengt an
        // der zeitlichen Abfolge, nicht an der Reihenfolge der Liste.
        val werte = listOf(messwert(3_000, 30.0), messwert(0, 40.0), messwert(1_000, 70.0))
        val k = AkustischeKennwerte.berechne(werte, ueberschreitungsSchwelleDb = 60.0)
        assertEquals(2_000L, k.ueberschreitungsdauerMs)
    }

    @Test
    fun sampleCountZaehltAlleWerte() {
        val k = AkustischeKennwerte.berechne(listOf(messwert(0, 1.0), messwert(1, 2.0), messwert(2, 3.0)))
        assertEquals(3, k.sampleCount)
    }

    @Test
    fun leqUndMaxLeereListeLiefertNullwerte() {
        val k = AkustischeKennwerte.leqUndMax(emptyList())
        assertNull(k.leqDb)
        assertNull(k.maxDb)
        assertEquals(0, k.sampleCount)
    }

    @Test
    fun leqUndMaxLiefertDenselbenLeqUndMaxWieBerechne() {
        // leqUndMax() ist der schnelle, unsortierte Pfad fuer das Live-Cockpit (PROMPT_M9A.md
        // Aufgabe 1) - er darf fuer LAeq und Max nichts anderes ausrechnen als berechne(),
        // nur eben ohne die beiden Sortierungen.
        val werte = listOf(
            messwert(0, 55.0), messwert(1, 80.0), messwert(2, 40.0), messwert(3, 65.0), messwert(4, 55.0),
        )
        val voll = AkustischeKennwerte.berechne(werte)
        val schnell = AkustischeKennwerte.leqUndMax(werte)

        assertEquals(voll.leqDb!!, schnell.leqDb!!, 0.0000001)
        assertEquals(voll.maxDb!!, schnell.maxDb!!, 0.0000001)
        assertEquals(voll.sampleCount, schnell.sampleCount)
    }

    @Test
    fun leqUndMaxLaesstPerzentileUndUeberschreitungsdauerBewusstAus() {
        // Wer L10/L50/L90 oder die Ueberschreitungsdauer braucht, muss weiterhin berechne()
        // aufrufen - leqUndMax() taeuscht diese Werte nicht mit einer erfundenen Naeherung vor.
        val k = AkustischeKennwerte.leqUndMax(listOf(messwert(0, 55.0), messwert(1, 65.0)))
        assertNull(k.minDb)
        assertNull(k.l10Db)
        assertNull(k.l50Db)
        assertNull(k.l90Db)
        assertEquals(0L, k.ueberschreitungsdauerMs)
    }

    @Test
    fun leqUndMaxFunktioniertUnabhaengigVonDerEingabereihenfolge() {
        // Anders als berechne() sortiert leqUndMax() nicht nach Zeit - Max und LAeq duerfen
        // trotzdem nicht von der Reihenfolge abhaengen.
        val sortiert = listOf(messwert(0, 40.0), messwert(1, 90.0), messwert(2, 60.0))
        val unsortiert = listOf(messwert(2, 60.0), messwert(0, 40.0), messwert(1, 90.0))

        val kSortiert = AkustischeKennwerte.leqUndMax(sortiert)
        val kUnsortiert = AkustischeKennwerte.leqUndMax(unsortiert)

        assertEquals(kSortiert.leqDb!!, kUnsortiert.leqDb!!, 0.0000001)
        assertEquals(kSortiert.maxDb!!, kUnsortiert.maxDb!!, 0.0000001)
        assertEquals(90.0, kUnsortiert.maxDb!!, 0.0001)
    }

    @Test
    fun leereAggregatlisteLiefertNullwerte() {
        val k = AkustischeKennwerte.ausAggregaten(emptyList())
        assertNull(k.leqDb)
        assertEquals(0, k.sampleCount)
    }

    @Test
    fun ausAggregatenIstEnergetischerMittelwertDerMinutenLeqWerte() {
        // Dieselbe Rechnung wie bei den Rohwerten, nur ueber bereits gemittelte Minuten-LAeq:
        // 60 dB und 70 dB energetisch ~ 67,40 dB, nicht 65 (arithmetisch).
        val k = AkustischeKennwerte.ausAggregaten(
            listOf(aggregat(0, leq = 60.0, max = 62.0, min = 58.0, samples = 100),
                aggregat(60_000, leq = 70.0, max = 72.0, min = 68.0, samples = 100))
        )
        assertEquals(67.4036, k.leqDb!!, 0.001)
    }

    @Test
    fun ausAggregatenNimmtMaxUndMinUeberAlleMinuten() {
        val k = AkustischeKennwerte.ausAggregaten(
            listOf(aggregat(0, leq = 60.0, max = 65.0, min = 55.0, samples = 10),
                aggregat(60_000, leq = 60.0, max = 90.0, min = 40.0, samples = 10))
        )
        assertEquals(90.0, k.maxDb!!, 0.0001)
        assertEquals(40.0, k.minDb!!, 0.0001)
    }

    @Test
    fun ausAggregatenSummiertSampleCountUeberAlleMinuten() {
        val k = AkustischeKennwerte.ausAggregaten(
            listOf(aggregat(0, leq = 60.0, max = 65.0, min = 55.0, samples = 100),
                aggregat(60_000, leq = 60.0, max = 65.0, min = 55.0, samples = 40))
        )
        assertEquals(140, k.sampleCount)
    }

    @Test
    fun ausAggregatenLiefertKeineL10L50L90UndKeineUeberschreitungsdauer() {
        // Ohne Rohwerte laesst sich das nicht mehr rekonstruieren - bewusst null/0 statt einer
        // erfundenen Naeherung.
        val k = AkustischeKennwerte.ausAggregaten(listOf(aggregat(0, leq = 60.0, max = 65.0, min = 55.0, samples = 10)))
        assertNull(k.l10Db)
        assertNull(k.l50Db)
        assertNull(k.l90Db)
        assertEquals(0L, k.ueberschreitungsdauerMs)
    }
}
