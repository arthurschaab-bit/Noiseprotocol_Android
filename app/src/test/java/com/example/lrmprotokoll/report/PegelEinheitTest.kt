package com.example.lrmprotokoll.report

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PROMPT_M9A.md Aufgabe 2: der Tagesbericht darf "dBA"/"dBC" nur schreiben, wenn die
 * Frequenzbewertung fuer GENAU diesen Datensatz bestaetigt war ([NoiseRecord.meterWeighting]
 * ist dann "A"/"C") - nicht schon deshalb, weil ueberhaupt ein kalibrierter Wert vorliegt.
 */
class PegelEinheitTest {

    @Test
    fun bestaetigteABewertungLiefertDbA() {
        assertEquals("dBA", pegelEinheit("A"))
    }

    @Test
    fun bestaetigteCBewertungLiefertDbC() {
        assertEquals("dBC", pegelEinheit("C"))
    }

    @Test
    fun unbestaetigteBewertungLiefertNurDb() {
        assertEquals("dB", pegelEinheit(null))
    }

    @Test
    fun unbekannterWertLiefertEbenfallsNurDbStattEineFalscheEinheitZuErfinden() {
        // Sollte praktisch nie vorkommen (meterWeighting kommt aus Weighting.name), aber
        // "dB" ist der sichere Rueckfall, keine erfundene Einheit.
        assertEquals("dB", pegelEinheit("irgendwas"))
    }
}
