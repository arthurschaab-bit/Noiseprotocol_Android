package com.example.lrmprotokoll.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROMPT_M9A.md Aufgabe 1: [berechneDbFensterAb] darf sich nicht bei jedem Sekundentick
 * aendern, sonst abonniert LiveCockpitCard den Messwert-Flow jede Sekunde neu - schlimmer als
 * der Ausgangszustand, nicht besser. Die eigentliche Kostenersparnis (weniger geladene Zeilen)
 * ist in [com.example.lrmprotokoll.data.MeasurementDaoTest] geprueft, hier nur die reine
 * Fensterarithmetik.
 */
class LiveCockpitFensterTest {

    private val sessionStart = 1_700_000_000_000L
    private val vierStunden = 4 * 3600 * 1000L
    private val fuenfMinuten = 5 * 60 * 1000L

    @Test
    fun kurzeLaufendeSessionLaedtAbSessionbeginn() {
        val ab = berechneDbFensterAb(
            sessionStart = sessionStart,
            sessionEndeOderJetzt = sessionStart + 60_000L,
            dienstAktiv = true,
        )
        assertEquals(sessionStart, ab)
    }

    @Test
    fun beendeteSessionLaedtImmerAbSessionbeginnAuchWennSieSehrLangeLief() {
        // 30 Stunden, aber dienstAktiv = false (Session ist zu Ende) - die volle Laenge muss
        // weiterhin ladbar sein, ein fertiger Chart darf nicht nachtraeglich verkuerzt wirken.
        val ab = berechneDbFensterAb(
            sessionStart = sessionStart,
            sessionEndeOderJetzt = sessionStart + 30 * 3600 * 1000L,
            dienstAktiv = false,
        )
        assertEquals(sessionStart, ab)
    }

    @Test
    fun langLaufendeSessionBegrenztAufDasFensterGerastert() {
        // 6 Stunden Laufzeit, Fenster 4 Stunden -> die exakte Grenze laege bei +2h, gerastert
        // auf 5-Minuten-Schritte darf das Ergebnis nicht NACH der exakten Grenze liegen.
        val jetzt = sessionStart + 6 * 3600 * 1000L
        val ab = berechneDbFensterAb(sessionStart, jetzt, dienstAktiv = true)
        val exakteGrenze = jetzt - vierStunden

        assertTrue("gerastertes Fenster darf nicht nach der exakten Grenze liegen", ab <= exakteGrenze)
        assertTrue("gerastertes Fenster soll nicht mehr als eine Rasterbreite verlieren", ab > exakteGrenze - fuenfMinuten)
        assertEquals(0L, (ab - sessionStart) % fuenfMinuten)
    }

    @Test
    fun sekuendlicheTicksInnerhalbDesselbenRastersLiefernDenselbenWert() {
        // Das ist der eigentliche Beweis: 60 aufeinanderfolgende Sekundentakte innerhalb
        // desselben 5-Minuten-Rasters duerfen NICHT 60 verschiedene Werte erzeugen - sonst
        // haette die Rasterung ihren Zweck verfehlt und der Flow wuerde weiterhin (fast) jede
        // Sekunde neu abonniert.
        val basisJetzt = sessionStart + 6 * 3600 * 1000L
        val ersterWert = berechneDbFensterAb(sessionStart, basisJetzt, dienstAktiv = true)

        for (sekunde in 1..60) {
            val spaetererWert = berechneDbFensterAb(sessionStart, basisJetzt + sekunde * 1_000L, dienstAktiv = true)
            assertEquals("Tick +${sekunde}s darf das Fenster nicht veraendern", ersterWert, spaetererWert)
        }
    }

    @Test
    fun ueberschreitenEinerRastergrenzeAendertDenWert() {
        val ersterWert = berechneDbFensterAb(sessionStart, sessionStart + 6 * 3600 * 1000L, dienstAktiv = true)
        val naechstesRaster = berechneDbFensterAb(sessionStart, sessionStart + 6 * 3600 * 1000L + fuenfMinuten, dienstAktiv = true)

        assertTrue("nach einer vollen Rasterbreite muss sich das Fenster weiterbewegen", naechstesRaster > ersterWert)
    }

    @Test
    fun genauAufDerFenstergrenzeWirdNochNichtEingeschraenkt() {
        // ">" in der Chart-Anzeige, nicht ">=" - dieselbe Grenzbedingung muss hier gelten.
        val ab = berechneDbFensterAb(sessionStart, sessionStart + vierStunden, dienstAktiv = true)
        assertEquals(sessionStart, ab)
    }
}
