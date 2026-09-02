package com.example.lrmprotokoll.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * KI-Umbau Etappe 2.4: Tests fuer die einzelnen Bausteine der Zeitaggregation
 * ([medianGlaettung], [hysterese], [ermittleBloecke]) - reine JVM-Tests, kein Geraet noetig.
 */
class ZeitaggregationTest {

    // --- medianGlaettung ---------------------------------------------------------------------

    @Test
    fun medianGlaettungLeeresArrayBleibtLeer() {
        assertEquals(0, medianGlaettung(FloatArray(0)).size)
    }

    @Test
    fun medianGlaettungGlaettetEinenEinzelnenAusreisser() {
        // Ein einzelner Spitzenwert zwischen niedrigen Nachbarn muss dem 3er-Median weichen.
        val scores = floatArrayOf(0.1f, 0.9f, 0.1f)

        val geglaettet = medianGlaettung(scores, fenster = 3)

        assertEquals(0.1f, geglaettet[1], 0.001f)
    }

    @Test
    fun medianGlaettungVerkuerztDasFensterAmRand() {
        // Am ersten Index gibt es keinen linken Nachbarn - das Fenster besteht dort nur aus
        // [0]/[1], nicht aus kuenstlich aufgefuellten Werten. Bei einem GERADEN Fenster (hier 2
        // Werte, da am Rand verkuerzt) waehlt die Implementierung den oberen der beiden sortierten
        // Werte (index size/2) statt eines gemittelten Medians - bei [0.8, 0.2] sortiert zu
        // [0.2, 0.8] also 0.8, nicht 0.5.
        val scores = floatArrayOf(0.8f, 0.2f, 0.2f)

        val geglaettet = medianGlaettung(scores, fenster = 3)

        assertEquals(0.8f, geglaettet[0], 0.001f)
    }

    @Test
    fun medianGlaettungLehntGeradesFensterAb() {
        assertThrows(IllegalArgumentException::class.java) { medianGlaettung(floatArrayOf(0.1f), fenster = 2) }
    }

    // --- hysterese -----------------------------------------------------------------------------

    @Test
    fun hystereseSteigtErstBeiEinSchwelleEin() {
        val scores = floatArrayOf(0.40f, 0.49f, 0.51f)

        val ueberSchwelle = hysterese(scores, einSchwelle = 0.50f, ausSchwelle = 0.35f)

        assertEquals(listOf(false, false, true), ueberSchwelle.toList())
    }

    @Test
    fun hystereseBleibtAktivBisAusSchwelleUnterschritten() {
        // Pendelt zwischen 0.40 und 0.50 - ohne Hysterese (Einheitsschwelle 0.50) wuerde das
        // flackern; mit Ausstieg erst bei 0.35 bleibt der Block zusammenhaengend.
        val scores = floatArrayOf(0.60f, 0.40f, 0.45f, 0.40f, 0.30f)

        val ueberSchwelle = hysterese(scores, einSchwelle = 0.50f, ausSchwelle = 0.35f)

        assertEquals(listOf(true, true, true, true, false), ueberSchwelle.toList())
    }

    // --- ermittleBloecke -------------------------------------------------------------------

    @Test
    fun ermittleBloeckeOhneTrefferErgibtLeereListe() {
        assertEquals(0, ermittleBloecke(booleanArrayOf(false, false, false)).size)
    }

    @Test
    fun ermittleBloeckeErkenntMehrereGetrennteBloecke() {
        val bloecke = ermittleBloecke(booleanArrayOf(true, true, false, false, true, false, true, true, true))

        assertEquals(3, bloecke.size)
        assertEquals(Block(0, 1), bloecke[0])
        assertEquals(Block(4, 4), bloecke[1])
        assertEquals(Block(6, 8), bloecke[2])
    }

    @Test
    fun ermittleBloeckeSchliesstEinenBlockAmEndeDesArraysKorrektAb() {
        val bloecke = ermittleBloecke(booleanArrayOf(false, true, true))

        assertEquals(listOf(Block(1, 2)), bloecke)
    }
}
