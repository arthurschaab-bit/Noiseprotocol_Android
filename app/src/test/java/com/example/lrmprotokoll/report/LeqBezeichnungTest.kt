package com.example.lrmprotokoll.report

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Prüfprotokoll-Befund 01 / Korrekturliste C-2, Owner-Entscheidung vom 11.09.2026: "Leq für
 * einen Mikrofonwert ist nicht sinnvoll" - [leqBezeichnung]/[lmaxBezeichnung] zentralisieren die
 * bereits vor dieser Korrektur auf den Bildschirmen (ProtokollScreen, ProtokollDetailScreen)
 * etablierte Unterscheidung "Mittelwert"/"Höchstwert" (Mikrofon) vs. "LAeq"/"LMax" (Messgerät),
 * damit die PDF-Exporte dieselbe Bezeichnung verwenden statt weiterhin unbedingt "LAeq" zu
 * schreiben. Dasselbe Prinzip wie [PegelEinheitTest]/[pegelEinheit]: keine Genauigkeit
 * vortäuschen, die nicht belegt ist.
 */
class LeqBezeichnungTest {

    @Test
    fun reinerMikrofonlaufBekommtMittelwertStattLAeq() {
        assertEquals("Mittelwert", leqBezeichnung(nurMikrofon = true))
    }

    @Test
    fun messgeraetBekommtWeiterhinLAeq() {
        assertEquals("LAeq", leqBezeichnung(nurMikrofon = false))
    }

    @Test
    fun reinerMikrofonlaufBekommtHoechstwertStattLMax() {
        assertEquals("Höchstwert", lmaxBezeichnung(nurMikrofon = true))
    }

    @Test
    fun messgeraetBekommtWeiterhinLMax() {
        assertEquals("LMax", lmaxBezeichnung(nurMikrofon = false))
    }
}
