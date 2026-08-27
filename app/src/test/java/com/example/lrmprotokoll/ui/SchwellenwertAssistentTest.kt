package com.example.lrmprotokoll.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PROMPT_M10_FUNKTIONEN.md F1: reine Ableitungslogik ohne Android-Abhängigkeit, deshalb als
 * reiner JUnit-Test ohne Robolectric, analog zu [LiveCockpitFensterTest]/[OemAutostartTest].
 */
class SchwellenwertAssistentTest {

    @Test
    fun aufAktuellemPegelUebernimmtDenPegelUnveraendert() {
        assertEquals(55f, schwellenvorschlagAufAktuellemPegel(55.0))
    }

    @Test
    fun mitSicherheitsabstandAddiertFuenfDb() {
        // Gegenprobe: schlaegt fehl, wenn der Sicherheitsabstand entfernt oder falsch addiert wird.
        assertEquals(60f, schwellenvorschlagMitSicherheitsabstand(55.0))
    }

    @Test
    fun aufAktuellemPegelKapptNachUntenAufDenSliderbereich() {
        assertEquals(30f, schwellenvorschlagAufAktuellemPegel(12.0))
    }

    @Test
    fun mitSicherheitsabstandKapptNachObenAufDenSliderbereich() {
        assertEquals(100f, schwellenvorschlagMitSicherheitsabstand(98.0))
    }

    @Test
    fun grenzwerteBleibenInnerhalbDesGueltigenBereichs() {
        assertEquals(30f, schwellenvorschlagAufAktuellemPegel(30.0))
        assertEquals(100f, schwellenvorschlagAufAktuellemPegel(100.0))
    }
}
