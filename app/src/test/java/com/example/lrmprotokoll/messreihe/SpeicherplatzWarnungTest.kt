package com.example.lrmprotokoll.messreihe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die Entscheidungsregel zu Befund F-10, ohne Android geprueft.
 *
 * Die Schwelle ist eine fachliche Aussage und stammt vom Owner (29.09.2026), nicht aus dem
 * Audit. Dieser Test haelt sie fest: wird sie kuenftig verschoben, faellt er auf und die
 * Aenderung muss bewusst passieren.
 */
class SpeicherplatzWarnungTest {
    private val gb = 1024L * 1024 * 1024

    @Test
    fun dieSchwelleLiegtBeiFuenfGigabyte() {
        assertEquals(5 * gb, SPEICHERPLATZ_WARNSCHWELLE_BYTES)
    }

    @Test
    fun unterhalbDerSchwelleWirdGewarnt() {
        assertTrue(sollVorSpeicherplatzWarnen(4 * gb))
        assertTrue(sollVorSpeicherplatzWarnen(0))
        assertTrue(sollVorSpeicherplatzWarnen(5 * gb - 1))
    }

    @Test
    fun aufDerSchwelleUndDarueberWirdNichtGewarnt() {
        assertFalse(sollVorSpeicherplatzWarnen(5 * gb))
        assertFalse(sollVorSpeicherplatzWarnen(50 * gb))
    }

    /**
     * Ohne Messwert wird **nicht** gewarnt. Eine Warnung auf einen geratenen Wert zu stuetzen
     * waere schlechter als keine: sie erschiene dann bei jedem Start, und der Nutzer gewoehnt
     * sich an, sie zu uebersehen.
     */
    @Test
    fun ohneMesswertWirdNichtGewarnt() {
        assertFalse(sollVorSpeicherplatzWarnen(null))
    }
}
