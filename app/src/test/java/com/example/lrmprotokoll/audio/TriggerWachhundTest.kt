package com.example.lrmprotokoll.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Der Wachhund existiert wegen eines realen Ausfalls: 12 Stunden Messung durchgehend ueber der
 * Schwelle, kein einziges Ereignis, und nichts an der App hat es angezeigt. Diese Tests halten
 * beide Richtungen fest - dass er anschlaegt, wenn die Ausloesung tot ist, und dass er es
 * nicht tut, wenn schlicht nichts los war.
 */
class TriggerWachhundTest {

    private val fenster = 10 * 60 * 1000L

    @Test
    fun schlaegtAnWennDauerhaftUeberDerSchwelleUndKeinEreignisEntsteht() {
        val wachhund = TriggerWachhund(fensterMs = fenster)

        var t = 0L
        while (t <= fenster) {
            wachhund.pegelGesehen(t, ueberSchwelle = true)
            t += 30_000L
        }

        assertTrue("Nach einem vollen Fenster ohne Ereignis muss der Wachhund anschlagen", wachhund.stillerAusfall(t))
    }

    @Test
    fun schlaegtNichtAnSolangeDasFensterNochNichtVollIst() {
        val wachhund = TriggerWachhund(fensterMs = fenster)
        wachhund.pegelGesehen(0L, ueberSchwelle = true)

        assertFalse(wachhund.stillerAusfall(fenster - 1))
        assertTrue(wachhund.stillerAusfall(fenster))
    }

    @Test
    fun eineRuhigeNachtIstKeinDefekt() {
        // Der wichtigste Gegentest. Ein Wachhund, der bei Stille anschlaegt, wird abgeschaltet -
        // und dann faengt er den echten Ausfall auch nicht mehr.
        val wachhund = TriggerWachhund(fensterMs = fenster)

        var t = 0L
        repeat(200) {
            wachhund.pegelGesehen(t, ueberSchwelle = false)
            t += 30_000L
        }

        assertFalse("Unter der Schwelle darf nie gemeldet werden", wachhund.stillerAusfall(t))
    }

    @Test
    fun einEreignisSetztDieStreckeZurueck() {
        val wachhund = TriggerWachhund(fensterMs = fenster)
        wachhund.pegelGesehen(0L, ueberSchwelle = true)
        wachhund.pegelGesehen(fenster / 2, ueberSchwelle = true)

        wachhund.ereignisGespeichert(fenster / 2)

        // Ohne das Zuruecksetzen waere bei t = fenster gemeldet worden, obwohl die Ausloesung
        // nachweislich funktioniert hat.
        assertFalse(wachhund.stillerAusfall(fenster))
    }

    @Test
    fun kurzUeberDerSchwelleDannLangeDarunterMeldetNicht() {
        val wachhund = TriggerWachhund(fensterMs = fenster)
        wachhund.pegelGesehen(0L, ueberSchwelle = true)
        wachhund.pegelGesehen(60_000L, ueberSchwelle = false)

        assertFalse(wachhund.stillerAusfall(fenster * 5))
    }

    @Test
    fun meldetNurEinmalJeStrecke() {
        // Eine Warnung, die im Minutentakt wiederkommt, wird zum Rauschen - und der naechste
        // echte Ausfall geht darin unter.
        val wachhund = TriggerWachhund(fensterMs = fenster)
        wachhund.pegelGesehen(0L, ueberSchwelle = true)

        assertTrue(wachhund.stillerAusfall(fenster))
        assertFalse("Zweite Abfrage derselben Strecke darf nicht erneut melden", wachhund.stillerAusfall(fenster + 60_000L))
        assertFalse(wachhund.stillerAusfall(fenster * 3))
    }

    @Test
    fun nachEinemEreignisKannErneutGemeldetWerden() {
        // Gegenprobe zu "nur einmal je Strecke": Ein spaeterer, zweiter Ausfall muss wieder
        // sichtbar werden.
        val wachhund = TriggerWachhund(fensterMs = fenster)
        wachhund.pegelGesehen(0L, ueberSchwelle = true)
        assertTrue(wachhund.stillerAusfall(fenster))

        wachhund.ereignisGespeichert(fenster)
        wachhund.pegelGesehen(fenster + 1_000L, ueberSchwelle = true)

        assertTrue(wachhund.stillerAusfall(fenster * 2 + 1_000L))
    }

    @Test
    fun zuruecksetzenVergisstDieLaufendeStrecke() {
        val wachhund = TriggerWachhund(fensterMs = fenster)
        wachhund.pegelGesehen(0L, ueberSchwelle = true)

        wachhund.zuruecksetzen()

        assertFalse(wachhund.stillerAusfall(fenster * 2))
    }

    @Test
    fun dauerUeberSchwelleWirdFuerDieMeldungMitgefuehrt() {
        val wachhund = TriggerWachhund(fensterMs = fenster)
        assertEquals(0L, wachhund.dauerUeberSchwelleMs(1_000L))

        wachhund.pegelGesehen(1_000L, ueberSchwelle = true)
        assertEquals(fenster, wachhund.dauerUeberSchwelleMs(1_000L + fenster))
    }
}
