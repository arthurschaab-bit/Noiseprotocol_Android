package com.example.lrmprotokoll.messreihe

import com.example.lrmprotokoll.meter.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun inaktiverDienstZeigtKeineDauerSelbstMitSessionStart() {
        val anzeige = leiteDashboardAnzeigeAb(
            dienstAktiv = false,
            geraetGepinnt = true,
            verbindungszustand = ConnectionState.STREAMING,
            sessionStartedAtMillis = 1_000L,
            jetztMillis = 65_000L,
            letzterPegel = 55.0,
        )

        assertNull(anzeige.dauerText)
        assertNull(anzeige.laufzeitText)
    }

    @Test
    fun aktiverDienstOhneSessionStartZeigtKeineDauer() {
        val anzeige = leiteDashboardAnzeigeAb(
            dienstAktiv = true,
            geraetGepinnt = false,
            verbindungszustand = ConnectionState.IDLE,
            sessionStartedAtMillis = null,
            jetztMillis = 65_000L,
            letzterPegel = null,
        )

        assertNull(anzeige.dauerText)
        assertNull(anzeige.laufzeitText)
    }

    @Test
    fun aktiverDienstMitOffenerSessionLiefertDauerText() {
        val anzeige = leiteDashboardAnzeigeAb(
            dienstAktiv = true,
            geraetGepinnt = true,
            verbindungszustand = ConnectionState.STREAMING,
            sessionStartedAtMillis = 10_000L,
            jetztMillis = 75_000L,
            letzterPegel = 60.0,
        )

        assertEquals("1:05", anzeige.dauerText)
        assertEquals("Läuft seit 1:05", anzeige.laufzeitText)
    }

    // S-3/F-02, Gerätetest A5/B4: vor dem Fix zeigte das Cockpit ohne laufende Messung "--.-",
    // obwohl das Messgeraet Werte lieferte.

    @Test
    fun verbundenOhneMessungZeigtDenKalibriertenPegelAlsNichtAufgezeichnet() {
        val anzeige =
            leitePegelAnzeigeAb(
                dienstAktiv = false,
                verbindungszustand = ConnectionState.STREAMING,
                messgeraetPegel = 63.4,
                mikrofonPegel = null,
            )

        assertEquals(63.4, anzeige.wert!!, 0.001)
        assertTrue("Ohne Aufzeichnung muss der Wert als nur-live gekennzeichnet sein", anzeige.nurLive)
        assertTrue("Der Wert kommt vom Messgeraet, ist also kalibriert", anzeige.kalibriert)
    }

    @Test
    fun laufendeMessungMitMessgeraetIstNichtNurLive() {
        val anzeige =
            leitePegelAnzeigeAb(
                dienstAktiv = true,
                verbindungszustand = ConnectionState.STREAMING,
                messgeraetPegel = 63.4,
                mikrofonPegel = 40.0,
            )

        assertEquals(63.4, anzeige.wert!!, 0.001)
        assertFalse("Bei laufender Aufzeichnung ist der Hinweis falsch", anzeige.nurLive)
        assertTrue(anzeige.kalibriert)
    }

    @Test
    fun ohneStreamingIstDerMessgeraetwertEinRestwertUndZaehltNicht() {
        val anzeige =
            leitePegelAnzeigeAb(
                dienstAktiv = false,
                verbindungszustand = ConnectionState.DISCONNECTED,
                messgeraetPegel = 63.4,
                mikrofonPegel = 40.0,
            )

        assertNull("Ein Pegel ohne STREAMING waere ein veralteter Restwert", anzeige.wert)
        assertFalse(anzeige.nurLive)
        assertFalse(anzeige.kalibriert)
    }

    @Test
    fun ohneMessgeraetBleibtDerMikrofonpegelAnDenDienstGebunden() {
        val mitDienst =
            leitePegelAnzeigeAb(
                dienstAktiv = true,
                verbindungszustand = ConnectionState.IDLE,
                messgeraetPegel = null,
                mikrofonPegel = 41.5,
            )
        val ohneDienst =
            leitePegelAnzeigeAb(
                dienstAktiv = false,
                verbindungszustand = ConnectionState.IDLE,
                messgeraetPegel = null,
                mikrofonPegel = 41.5,
            )

        assertEquals(41.5, mitDienst.wert!!, 0.001)
        assertFalse("Der Mikrofonpegel ist unkalibriert", mitDienst.kalibriert)
        assertNull("Ohne Aufzeichnung gibt es keinen Mikrofonpegel", ohneDienst.wert)
    }

    @Test
    fun streamingOhneFrameFaelltAufDenMikrofonpegelZurueck() {
        val anzeige =
            leitePegelAnzeigeAb(
                dienstAktiv = true,
                verbindungszustand = ConnectionState.STREAMING,
                messgeraetPegel = null,
                mikrofonPegel = 42.0,
            )

        assertEquals(42.0, anzeige.wert!!, 0.001)
        assertFalse(anzeige.kalibriert)
    }
}
