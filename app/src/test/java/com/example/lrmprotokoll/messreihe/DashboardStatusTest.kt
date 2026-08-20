package com.example.lrmprotokoll.messreihe

import com.example.lrmprotokoll.meter.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardStatusTest {

    @Test
    fun inaktiverDienstZeigtNurInaktivUnabhaengigVonAllemAnderen() {
        val anzeige = leiteDashboardAnzeigeAb(
            dienstAktiv = false, geraetGepinnt = true, verbindungszustand = ConnectionState.STREAMING,
            sessionStartedAtMillis = 1_000L, jetztMillis = 5_000L, letzterPegel = 55.0,
        )

        assertEquals(false, anzeige.dienstAktiv)
        assertEquals("Inaktiv", anzeige.betriebsartText)
        assertNull(anzeige.laufzeitText)
        assertNull(anzeige.pegelText)
    }

    @Test
    fun mikrofonNurFallOhneGepinntesGeraetZeigtMikrofonBetriebsart() {
        val anzeige = leiteDashboardAnzeigeAb(
            dienstAktiv = true, geraetGepinnt = false, verbindungszustand = ConnectionState.IDLE,
            sessionStartedAtMillis = null, jetztMillis = 5_000L, letzterPegel = null,
        )

        assertEquals("Mikrofon-Überwachung", anzeige.betriebsartText)
        assertNull("Ohne gepinntes Geraet gibt es keine Messreihen-Session", anzeige.laufzeitText)
    }

    @Test
    fun gepinntesGeraetZeigtVerbindungszustandAlsBetriebsart() {
        val anzeige = leiteDashboardAnzeigeAb(
            dienstAktiv = true, geraetGepinnt = true, verbindungszustand = ConnectionState.RECONNECTING,
            sessionStartedAtMillis = null, jetztMillis = 5_000L, letzterPegel = null,
        )

        assertEquals("Messgerät: Verbinde erneut…", anzeige.betriebsartText)
    }

    @Test
    fun laufzeitWirdAusSessionStartUndJetztBerechnet() {
        val anzeige = leiteDashboardAnzeigeAb(
            dienstAktiv = true, geraetGepinnt = true, verbindungszustand = ConnectionState.STREAMING,
            sessionStartedAtMillis = 0L, jetztMillis = 65_000L, letzterPegel = null,
        )

        assertEquals("Läuft seit 1:05", anzeige.laufzeitText)
    }

    @Test
    fun pegelNurBeiStreamingAngezeigt() {
        val nichtStreaming = leiteDashboardAnzeigeAb(
            dienstAktiv = true, geraetGepinnt = true, verbindungszustand = ConnectionState.DEGRADED,
            sessionStartedAtMillis = null, jetztMillis = 0L, letzterPegel = 55.0,
        )
        val streaming = leiteDashboardAnzeigeAb(
            dienstAktiv = true, geraetGepinnt = true, verbindungszustand = ConnectionState.STREAMING,
            sessionStartedAtMillis = null, jetztMillis = 0L, letzterPegel = 55.0,
        )

        assertNull(
            "Ein Pegel ohne STREAMING waere ein veralteter Restwert aus einer frueheren Verbindung",
            nichtStreaming.pegelText,
        )
        assertEquals("55,0 dB", streaming.pegelText)
    }

    @Test
    fun formatiereDauerUnterEinerStundeZeigtNurMinutenUndSekunden() {
        assertEquals("0:00", formatiereDauer(0))
        assertEquals("0:09", formatiereDauer(9_000))
        assertEquals("1:05", formatiereDauer(65_000))
        assertEquals("59:59", formatiereDauer(59 * 60_000 + 59_000))
    }

    @Test
    fun formatiereDauerAbEinerStundeZeigtStunden() {
        assertEquals("1:00:00", formatiereDauer(3_600_000))
        assertEquals("2:03:04", formatiereDauer(2 * 3_600_000 + 3 * 60_000 + 4_000))
    }

    @Test
    fun formatiereDauerKapptNegativeEingabenAufNull() {
        assertEquals("0:00", formatiereDauer(-5_000))
    }
}
