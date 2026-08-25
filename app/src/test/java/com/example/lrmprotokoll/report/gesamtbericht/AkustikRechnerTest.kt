package com.example.lrmprotokoll.report.gesamtbericht

import com.example.lrmprotokoll.report.gesamtbericht.calc.AkustikRechner
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class AkustikRechnerTest {

    @Test
    fun testLaeqBerechnung() {
        // Zwei gleiche Pegel (z.B. 60 dB und 60 dB) ergeben genau 60 dB
        val laeqGleich = AkustikRechner.berechneLaeq(listOf(60.0, 60.0))
        assertNotNull(laeqGleich)
        assertEquals(60.0, laeqGleich!!, 0.01)

        // 50 dB und 56 dB (energetische Mittelung)
        // 10*log10( (10^5 + 10^5.6)/2 ) = 10*log10( (100000 + 398107)/2 ) = 10*log10(249053.5) = 53.96 dB
        val laeqMisch = AkustikRechner.berechneLaeq(listOf(50.0, 56.0))
        assertNotNull(laeqMisch)
        assertEquals(53.96, laeqMisch!!, 0.1)

        // Leere Liste
        assertNull(AkustikRechner.berechneLaeq(emptyList()))
    }

    @Test
    fun testPerzentileBerechnung() {
        val werte = (1..100).map { it.toDouble() } // 1.0 bis 100.0
        val p = AkustikRechner.berechnePerzentile(werte)

        assertEquals(99.0, p[1] ?: 0.0, 1.0)
        assertEquals(90.0, p[10] ?: 0.0, 1.0)
        assertEquals(50.0, p[50] ?: 0.0, 1.0)
        assertEquals(10.0, p[90] ?: 0.0, 1.0)
        assertEquals(5.0, p[95] ?: 0.0, 1.0)
    }

    @Test
    fun testPegelklassen() {
        val pegel = listOf(45.0, 52.0, 58.0, 62.0, 68.0, 75.0)
        val klassen = AkustikRechner.berechnePegelklassen(pegel, sampleDauerSekunden = 1.0)

        assertEquals(1L, klassen["<50"])
        assertEquals(1L, klassen["50-55"])
        assertEquals(1L, klassen["55-60"])
        assertEquals(1L, klassen["60-65"])
        assertEquals(1L, klassen["65-70"])
        assertEquals(1L, klassen[">70"])
    }

    @Test
    fun testSha256() {
        val hash = AkustikRechner.berechneSha256("Testdaten für Revisionssicherheit")
        assertNotNull(hash)
        assertEquals(64, hash.length)
    }
}
