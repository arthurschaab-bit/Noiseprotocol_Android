package com.example.lrmprotokoll.audio

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeakNormalisierungTest {

    @Test
    fun leerePufferBleibtUnveraendert() {
        val ergebnis = normalisierePeak(ShortArray(0))

        assertEquals(0, ergebnis.size)
    }

    @Test
    fun stilleBleibtStilleStattDurchDivisionDurchNullZuEskalieren() {
        val stille = ShortArray(100) { 0 }

        val ergebnis = normalisierePeak(stille)

        assertTrue(ergebnis.all { it == 0.toShort() })
    }

    @Test
    fun leiserClipWirdAufDenZielpeakHochskaliert() {
        // Peak bei 10% der Aussteuerung (3277 von 32767) - deutlich leiser als das Ziel.
        val leise = shortArrayOf(3277, -3277, 1000, -1000, 0)

        val ergebnis = normalisierePeak(leise, zielPeak = 0.95f)

        val neuerPeak = ergebnis.maxOf { abs(it.toInt()) }
        val erwarteterPeak = (0.95f * Short.MAX_VALUE).toInt()
        assertTrue(
            "Neuer Peak ($neuerPeak) muss nah am Ziel-Peak ($erwarteterPeak) liegen",
            abs(neuerPeak - erwarteterPeak) <= 1,
        )
    }

    @Test
    fun bereitsLauterAlsDasZielWirdHerunterskaliert() {
        val voll = shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE)

        val ergebnis = normalisierePeak(voll, zielPeak = 0.95f)

        val neuerPeak = ergebnis.maxOf { abs(it.toInt()) }
        val erwarteterPeak = (0.95f * Short.MAX_VALUE).toInt()
        assertTrue(
            "Ein bereits voll ausgesteuerter Clip muss auf den Zielpeak HERUNTER skaliert werden " +
                "(echte Peak-Normalisierung, nicht nur ein Verstaerker)",
            abs(neuerPeak - erwarteterPeak) <= 1,
        )
    }

    @Test
    fun relativeVerhaeltnisseZwischenSamplesBleibenErhalten() {
        val samples = shortArrayOf(1000, -500, 250)

        val ergebnis = normalisierePeak(samples, zielPeak = 0.95f)

        // Das Verhaeltnis 1000 : -500 : 250 = 4 : -2 : 1 darf durch die lineare Skalierung nicht
        // verzerrt werden - Toleranz statt exakter Gleichheit, weil jedes Sample unabhaengig
        // gerundet wird.
        val toleranz = 1.0
        assertTrue(abs(ergebnis[1].toDouble() - (-ergebnis[0].toDouble() / 2)) <= toleranz)
        assertTrue(abs(ergebnis[2].toDouble() - (ergebnis[0].toDouble() / 4)) <= toleranz)
    }

    @Test
    fun ergebnisUeberschreitetNieDenShortWertebereich() {
        val samples = shortArrayOf(1, -1, 32767, -32768)

        val ergebnis = normalisierePeak(samples, zielPeak = 1.0f)

        ergebnis.forEach {
            assertTrue(it >= Short.MIN_VALUE && it <= Short.MAX_VALUE)
        }
    }
}
