package com.example.lrmprotokoll.report.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Der Grund, warum [Seitenlauf] ueberhaupt eine eigene Klasse ist: `PdfDocument` ist unter
 * Robolectric nicht instanziierbar, die Umbruchentscheidung aber reine Arithmetik. Diese Tests
 * laufen unter plain JUnit und decken damit genau den Teil ab, den der bisherige Code
 * ueberhaupt nicht hatte - beide alten PDF-Exporte liessen Inhalt am unteren Seitenrand
 * kommentarlos abreissen.
 */
class SeitenlaufTest {

    @Test
    fun einKurzerBerichtBleibtAufEinerSeite() {
        val lauf = Seitenlauf()
        repeat(10) { lauf.platziere(20f) }
        assertEquals(1, lauf.seitenNummer)
    }

    @Test
    fun zuVieleZeilenBrechenAufDieNaechsteSeiteUm() {
        val lauf = Seitenlauf()
        val nutzhoehe = lauf.unterkante - Seitenlauf.RAND_OBEN
        val zeilen = (nutzhoehe / 20f).toInt() + 1

        repeat(zeilen) { lauf.platziere(20f) }

        assertEquals("Eine Zeile mehr als auf die Seite passt muss umbrechen", 2, lauf.seitenNummer)
    }

    @Test
    fun nachDemUmbruchBeginntDerInhaltWiederAmOberenRand() {
        val lauf = Seitenlauf()
        while (lauf.seitenNummer == 1) lauf.platziere(50f)
        assertTrue(lauf.y <= Seitenlauf.RAND_OBEN + 50f)
    }

    @Test
    fun seitenwechselWerdenGenauEinmalGemeldet() {
        val gemeldet = mutableListOf<Int>()
        val lauf = Seitenlauf(onNeueSeite = { gemeldet += it })

        repeat(200) { lauf.platziere(20f) }

        // Fortlaufend ab 1, ohne Dublette und ohne Luecke - der Renderer verlaesst sich darauf,
        // dass er je Seite genau einmal Kopf- und Fusszeile zeichnet.
        assertEquals((1..lauf.seitenNummer).toList(), gemeldet)
    }

    @Test
    fun dieErsteSeiteWirdAuchOhneUmbruchGemeldet() {
        // Sonst bekaeme ein einseitiger Bericht weder Kopf- noch Fusszeile.
        val gemeldet = mutableListOf<Int>()
        Seitenlauf(onNeueSeite = { gemeldet += it }).platziere(10f)
        assertEquals(listOf(1), gemeldet)
    }

    @Test
    fun einBlockGroesserAlsEineSeiteErzeugtKeineEndlosschleife() {
        // Vorsaetzlich pathologisch: haette platziere() stumpf "passt nicht -> neue Seite"
        // gemacht, ohne die leere Seite auszunehmen, liefe das hier ewig.
        val lauf = Seitenlauf()
        lauf.platziere(5000f)
        assertEquals(1, lauf.seitenNummer)
    }

    @Test
    fun neueSeiteAufLeererSeiteErzeugtKeinLeerblatt() {
        val lauf = Seitenlauf()
        lauf.neueSeite()
        assertEquals(1, lauf.seitenNummer)

        lauf.platziere(20f)
        lauf.neueSeite()
        assertEquals(2, lauf.seitenNummer)
    }

    @Test
    fun zaehleSeitenLiefertDieselbeAnzahlWieDerEchteDurchlauf() {
        // Das ist die Zusage, auf der "Seite N von M" beruht: Zaehl- und Zeichendurchlauf
        // muessen bei identischem Aufbau identisch umbrechen.
        val aufbau: (Seitenlauf) -> Unit = { lauf ->
            repeat(120) { lauf.platziere(18f) }
            lauf.neueSeite()
            repeat(40) { lauf.platziere(22f) }
        }

        val gezaehlt = Seitenlauf.zaehleSeiten(aufbau)
        val gezeichnet = Seitenlauf().also(aufbau).seitenNummer

        assertEquals(gezaehlt, gezeichnet)
        assertTrue("Der Aufbau muss mehrseitig sein, sonst prueft der Test nichts", gezaehlt > 1)
    }

    @Test
    fun abstandLoestKeinenUmbruchAus() {
        val lauf = Seitenlauf()
        lauf.platziere(20f)
        val vorher = lauf.seitenNummer
        lauf.abstand(10f)
        assertEquals(vorher, lauf.seitenNummer)
    }
}
