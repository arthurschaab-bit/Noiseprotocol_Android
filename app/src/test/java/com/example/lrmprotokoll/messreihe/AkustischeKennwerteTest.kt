package com.example.lrmprotokoll.messreihe

import com.example.lrmprotokoll.data.MeasurementEntity
import com.example.lrmprotokoll.data.MinuteAggregateEntity
import java.time.Duration
import kotlin.math.log10
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
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

    // ---------------------------------------------------------------- Zeitgewichtung (Befund 01 / C-2)

    /** Energetischer Mittelwert OHNE Zeitgewichtung - die Formel, die vor der Korrektur galt.
     * Fuer den Vergleich, nicht fuer die Produktionslogik. */
    private fun naivesLeq(werte: List<MeasurementEntity>) =
        10.0 * log10(werte.sumOf { 10.0.pow(it.levelDb / 10.0) } / werte.size)

    @Test
    fun zeitgewichtungLaesstKurzenLautenAusreisserWenigerDominieren() {
        // Ein 90-dB-Ausreisser, der nur 100 ms lang war, gefolgt von 40 dB ueber satte 10 s
        // (zwei Messwerte im Abstand von 5 s, dem Standard-Kappungswert - siehe naechster Test).
        // Die alte, ungewichtete Formel behandelte alle drei Messwerte gleich - der kurze
        // Ausreisser dominierte das Ergebnis, obwohl er kaum einen Bruchteil der Zeit ausmachte.
        val werte = listOf(
            messwert(0, 90.0),
            messwert(100, 40.0),
            messwert(5_100, 40.0),
        )
        val gewichtet = AkustischeKennwerte.berechne(werte).leqDb!!
        val naiv = naivesLeq(werte)

        assertTrue(
            "Naiv (ungewichtet) muss vom kurzen 90-dB-Ausreisser dominiert werden, war $naiv",
            naiv > 80.0,
        )
        assertTrue(
            "Zeitgewichtet muss deutlich niedriger liegen als naiv (90 dB dauerte nur 100 ms von 5100 ms) - " +
                "gewichtet=$gewichtet, naiv=$naiv",
            gewichtet < naiv - 10.0,
        )
        assertTrue("Zeitgewichtet muss klar naeher an 40 dB liegen als an 90 dB, war $gewichtet", gewichtet < 75.0)
    }

    @Test
    fun zeitgewichtungKapptSehrGrosseLueckenAufDenselbenWertUnabhaengigVonIhrerGroesse() {
        // Eine Luecke von genau 5 s (Standard-Kappung) und eine 100x groessere Luecke muessen
        // nach der Kappung dasselbe Gewicht haben - sonst wuerde ein alter Ausfall (z.B. eine
        // ganze Nacht ohne Frame) den zuletzt bekannten Pegel absurd dominieren lassen.
        fun mitLuecke(luecke: Long) = listOf(
            messwert(0, 40.0),
            messwert(500, 90.0),
            messwert(500 + luecke, 40.0),
        )

        val amKappungswert = AkustischeKennwerte.berechne(mitLuecke(5_000)).leqDb!!
        val weitUeberKappungswert = AkustischeKennwerte.berechne(mitLuecke(500_000)).leqDb!!

        assertEquals(
            "Nach der Kappung auf 5 s duerfen eine 5-s- und eine 500-s-Luecke keinen " +
                "unterschiedlichen Leq mehr ergeben",
            amKappungswert, weitUeberKappungswert, 0.0000001,
        )
    }

    @Test
    fun maxGewichtungslueckeParameterWirdTatsaechlichVerwendet() {
        val werte = listOf(messwert(0, 40.0), messwert(500, 90.0), messwert(500_500, 40.0))

        val mitStandardkappung = AkustischeKennwerte.berechne(werte).leqDb!!
        val mitKleinererKappung = AkustischeKennwerte.berechne(
            werte, maxGewichtungsluecke = Duration.ofMillis(50),
        ).leqDb!!

        assertNotEquals(
            "Ein anderer maxGewichtungsluecke-Wert muss auch ein anderes Ergebnis liefern - " +
                "sonst wird der Parameter nicht wirklich verwendet",
            mitStandardkappung, mitKleinererKappung,
        )
    }

    @Test
    fun leqUndMaxGewichtetAuchBeiUnsortierterEingabeKorrektAufBasisDerZeit() {
        // Dieselben, absichtlich UNGLEICHEN Zeitabstaende wie oben, aber diesmal ueber
        // leqUndMax() und in vertauschter Reihenfolge eingespeist - muss trotzdem exakt das
        // Ergebnis liefern, das berechne() fuer die (richtig sortierte) Liste liefert.
        val sortiert = listOf(messwert(0, 90.0), messwert(100, 40.0), messwert(5_100, 40.0))
        val unsortiert = listOf(sortiert[2], sortiert[0], sortiert[1])

        val erwartet = AkustischeKennwerte.berechne(sortiert).leqDb!!
        val tatsaechlich = AkustischeKennwerte.leqUndMax(unsortiert).leqDb!!

        assertEquals(erwartet, tatsaechlich, 0.0000001)
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
