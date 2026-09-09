package com.example.lrmprotokoll.messreihe

import org.junit.Assert.assertEquals
import org.junit.Test

class DatenverfuegbarkeitTest {

    @Test
    fun ohneAusfallbaenderHundertProzent() {
        val prozent = berechneDatenverfuegbarkeitProzent(1000L, 2000L, emptyList())
        assertEquals(100.0, prozent, 0.001)
    }

    @Test
    fun vollstaendigerAusfallNullProzent() {
        val prozent = berechneDatenverfuegbarkeitProzent(
            1000L, 2000L, listOf(Ausfallband(1000L, 2000L)),
        )
        assertEquals(0.0, prozent, 0.001)
    }

    @Test
    fun teilweiserAusfallErgibtAnteiligeVerfuegbarkeit() {
        // 1000 ms Zeitraum, 250 ms Ausfall -> 75 % verfuegbar.
        val prozent = berechneDatenverfuegbarkeitProzent(
            1000L, 2000L, listOf(Ausfallband(1000L, 1250L)),
        )
        assertEquals(75.0, prozent, 0.001)
    }

    @Test
    fun ausfallbandVollstaendigAusserhalbDesZeitraumsWirdIgnoriert() {
        val prozent = berechneDatenverfuegbarkeitProzent(
            1000L, 2000L, listOf(Ausfallband(3000L, 4000L)),
        )
        assertEquals(100.0, prozent, 0.001)
    }

    @Test
    fun ausfallbandRagtVorneUeberDenZeitraumHinausUndWirdGekappt() {
        // Band beginnt vor `von` und endet 500 ms nach `von` - nur die ueberlappenden 500 ms
        // duerfen als Ausfall im Zeitraum zaehlen, nicht die volle Banddauer.
        val prozent = berechneDatenverfuegbarkeitProzent(
            1000L, 2000L, listOf(Ausfallband(500L, 1500L)),
        )
        assertEquals(50.0, prozent, 0.001)
    }

    @Test
    fun offenesAusfallbandOhneEndeZaehltBisZumZeitraumsende() {
        val prozent = berechneDatenverfuegbarkeitProzent(
            1000L, 2000L, listOf(Ausfallband(1500L, null)),
        )
        assertEquals(50.0, prozent, 0.001)
    }

    @Test
    fun mehrereAusfallbaenderWerdenAufsummiert() {
        val prozent = berechneDatenverfuegbarkeitProzent(
            0L, 1000L, listOf(Ausfallband(0L, 100L), Ausfallband(500L, 600L)),
        )
        assertEquals(80.0, prozent, 0.001)
    }

    @Test
    fun leererZeitraumErgibtNullProzentStattDivisionDurchNull() {
        val prozent = berechneDatenverfuegbarkeitProzent(1000L, 1000L, emptyList())
        assertEquals(0.0, prozent, 0.001)
    }
}
