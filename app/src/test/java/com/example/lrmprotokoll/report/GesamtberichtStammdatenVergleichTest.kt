package com.example.lrmprotokoll.report

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GesamtberichtStammdatenVergleichTest {
    private val daten =
        GesamtberichtStammdaten(
            geraetHersteller = "NTI Audio",
            geraetTyp = "XL2",
            geraetGenauigkeitsklasse = "Klasse 1",
            geraetSeriennummer = "aB123",
            geraetKalibrierung = "94 dB(A)",
            messort = "Musterstraße 1",
            mikrofonposition = "Fensterbank",
            mikrofonhoehe = "1,5 m",
            entfernungZurQuelle = "3 m",
            innenAussen = "Innen",
            fensterzustand = "geschlossen",
            wetter = "12 °C, bedeckt",
            datenqualitaetHinweis = "Keine Lücken",
        )

    @Test
    fun ignoriertMetadatenUndUnwichtigeSchreibweisen() {
        val geaendert =
            daten.copy(
                geraetHersteller = "  nti   AUDIO ",
                wetter = "  12 °c,   BEDECKT  ",
            )
        val gespeichert = geaendert.zuEntity(erstelltAm = 123L)
        val eintrag = gespeichert.copy(id = 99L, giltFuerTagStart = 456L)

        assertTrue(daten.entsprichtEintrag(eintrag))
    }

    @Test
    fun erkenntJedesFachlichGeaenderteFeld() {
        val basis = daten.zuEntity(erstelltAm = 123L)
        val aenderungen =
            listOf(
                basis.copy(geraetHersteller = "anders"),
                basis.copy(geraetTyp = "anders"),
                basis.copy(geraetGenauigkeitsklasse = "anders"),
                basis.copy(geraetSeriennummer = "AB123"),
                basis.copy(geraetKalibrierung = "anders"),
                basis.copy(messort = "anders"),
                basis.copy(mikrofonposition = "anders"),
                basis.copy(mikrofonhoehe = "anders"),
                basis.copy(entfernungZurQuelle = "anders"),
                basis.copy(innenAussen = "anders"),
                basis.copy(fensterzustand = "anders"),
                basis.copy(wetter = "anders"),
                basis.copy(datenqualitaetHinweis = "anders"),
            )

        aenderungen.forEach { assertFalse(daten.entsprichtEintrag(it)) }
    }
}
