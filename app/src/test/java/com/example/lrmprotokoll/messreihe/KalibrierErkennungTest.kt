package com.example.lrmprotokoll.messreihe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KalibrierErkennungTest {
    private val schritt = 500L // Frame-Takt des PCE-323 ist ~515 ms

    /** Speist [sekunden] Sekunden konstanten Pegel ein und liefert den neuen Zeitstand. */
    private fun KalibrierErkennung.halte(start: Long, sekunden: Int, pegel: (Int) -> Double): Long {
        var t = start
        repeat(sekunden * 1000 / schritt.toInt()) { i ->
            nimm(t, pegel(i))
            t += schritt
        }
        return t
    }

    @Test
    fun stabilesPlateauLiefertVorherUndNachherAlsAbweichungZumSollwert() {
        val erkennung = KalibrierErkennung()
        erkennung.halte(0, 10) { 93.4 }

        val anzeige = erkennung.anzeige()
        assertEquals(-0.6, anzeige.abweichungVorherDb!!, 0.001)
        assertEquals(-0.6, anzeige.abweichungNachherDb!!, 0.001)
        assertFalse(anzeige.endeErkannt)
    }

    @Test
    fun nachDerJustierungWirdDasNeuePlateauAlsNachherUebernommenVorherBleibt() {
        val erkennung = KalibrierErkennung()
        var t = erkennung.halte(0, 8) { 92.6 } // erstes Plateau: -1,4 dB
        t = erkennung.halte(t, 3) { 92.6 + it * 0.4 } // Nutzer dreht, Pegel steigt
        erkennung.halte(t, 9) { 94.0 } // neues Plateau

        val anzeige = erkennung.anzeige()
        assertEquals(-1.4, anzeige.abweichungVorherDb!!, 0.001)
        assertEquals(0.0, anzeige.abweichungNachherDb!!, 0.001)
    }

    @Test
    fun einschwingenDesKalibratorsMitDriftIstKeinPlateau() {
        val erkennung = KalibrierErkennung()
        // 6 s, die um 3 dB driften: die Spannweite ueberschreitet 1 dB
        erkennung.halte(0, 6) { 91.5 + it * 0.25 }

        assertNull(erkennung.anzeige().abweichungVorherDb)
    }

    @Test
    fun pegelAusserhalbVon92Bis96ZaehltNichtAlsPlateau() {
        val erkennung = KalibrierErkennung()
        erkennung.halte(0, 10) { 105.0 } // 104-dB-Stufe des Kalibrators

        assertNull(erkennung.anzeige().abweichungVorherDb)
    }

    @Test
    fun sprungzwischenStufenNachDemPlateauAendertVorherUndNachherNicht() {
        val erkennung = KalibrierErkennung()
        var t = erkennung.halte(0, 8) { 94.3 }
        t = erkennung.halte(t, 4) { if (it % 2 == 0) 105.0 else 114.8 }
        erkennung.halte(t, 2) { 94.3 }

        val anzeige = erkennung.anzeige()
        assertEquals(0.3, anzeige.abweichungVorherDb!!, 0.001)
        assertEquals(0.3, anzeige.abweichungNachherDb!!, 0.001)
        assertFalse(anzeige.endeErkannt)
    }

    @Test
    fun endeWirdErstNachPlateauUndAnhaltendNiedrigemPegelErkannt() {
        val erkennung = KalibrierErkennung()
        // Niedriger Pegel ohne vorheriges Plateau ist noch kein Ende (Messung hat nicht begonnen).
        var t = erkennung.halte(0, 6) { 55.0 }
        assertFalse(erkennung.anzeige().endeErkannt)

        t = erkennung.halte(t, 8) { 94.0 }
        t = erkennung.halte(t, 2) { 60.0 } // kurz, unter endeDauerMs
        assertFalse(erkennung.anzeige().endeErkannt)
        t = erkennung.halte(t, 1) { 94.0 } // wieder am Kalibrator
        erkennung.halte(t, 5) { 58.0 }
        assertTrue(erkennung.anzeige().endeErkannt)
    }

    @Test
    fun luckeImDatenstromVerwirftDasFensterUndEinKurzesFensterIstKeinPlateau() {
        val erkennung = KalibrierErkennung()
        erkennung.halte(0, 3) { 94.0 }
        erkennung.nimm(20_000, 94.0) // 17 s Luecke, danach nur ein Wert
        assertNull(erkennung.anzeige().abweichungVorherDb)
    }
}
