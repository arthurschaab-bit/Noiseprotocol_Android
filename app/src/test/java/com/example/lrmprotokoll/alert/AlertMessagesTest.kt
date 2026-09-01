package com.example.lrmprotokoll.alert

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testluecken-Auftrag Stufe 3: reine Textbausteine, aber mit einer harten Nebenbedingung aus
 * Plan 7.4 ("keine Messwerte, keine Orte, keine Geraetekennungen im Alarmtext") - ein Alarmtext,
 * der mehr verraet als noetig, ist beim oeffentlichen ntfy-Server (Topic = einzige
 * Zugangskontrolle) ein Datenleck. Kein Robolectric noetig, reines java.time.
 */
class AlertMessagesTest {

    private val zeit = Instant.parse("2026-08-19T13:37:00Z")
    private val zone: ZoneId = ZoneId.of("Europe/Berlin")

    @Test
    fun raisedNenntZeitpunktUndGrund() {
        val text = AlertMessages.formatiere(AlertKind.RAISED, AlertReason.STALE, zeit, zone)

        assertTrue(text.contains("15:37")) // 13:37 UTC = 15:37 MESZ
        assertTrue(text.contains("keine Daten"))
        assertTrue(text.contains("pausiert"))
    }

    @Test
    fun escalatedNenntEbenfallsZeitpunktUndGrundAberOhnePausenhinweis() {
        val text = AlertMessages.formatiere(AlertKind.ESCALATED, AlertReason.DISCONNECTED, zeit, zone)

        assertTrue(text.contains("15:37"))
        assertTrue(text.contains("Verbindung abgebrochen"))
        assertFalse(
            "ESCALATED wiederholt nur, es ist keine neue Zustandsänderung wie RAISED",
            text.contains("pausiert"),
        )
    }

    @Test
    fun resolvedNenntSeitWannDerAusfallBestand() {
        val text = AlertMessages.formatiere(AlertKind.RESOLVED, AlertReason.ADAPTER_OFF, zeit, zone)

        assertTrue(text.contains("wieder hergestellt"))
        assertTrue(text.contains("15:37"))
    }

    @Test
    fun testNachrichtEnthaeltWederZeitNochGrund() {
        val text = AlertMessages.formatiere(AlertKind.TEST, AlertReason.RECONNECT_EXHAUSTED, zeit, zone)

        assertEquals("Lärmprotokoll: Testnachricht. Dieser Alarmkanal funktioniert.", text)
    }

    @Test
    fun keineAlarmtextVarianteEnthaeltEinenMesswertOderEineGeraeteadresse() {
        for (kind in AlertKind.entries) {
            for (reason in AlertReason.entries) {
                val text = AlertMessages.formatiere(kind, reason, zeit, zone)
                assertFalse(
                    "Plan 7.4: kein Messwert im Alarmtext - gefunden in '$text'",
                    Regex("""\d+[.,]\d+\s*dB""").containsMatchIn(text),
                )
                assertFalse(
                    "Plan 7.4: keine MAC-Adresse im Alarmtext - gefunden in '$text'",
                    Regex("""([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}""").containsMatchIn(text),
                )
            }
        }
    }

    @Test
    fun titelUnterscheidetVerlorenUndWiederDa() {
        assertEquals("Lärmprotokoll: Verbindung verloren", AlertMessages.titel(AlertKind.RAISED))
        assertEquals("Lärmprotokoll: Verbindung verloren", AlertMessages.titel(AlertKind.ESCALATED))
        assertEquals("Lärmprotokoll: Verbindung wieder da", AlertMessages.titel(AlertKind.RESOLVED))
        assertEquals("Lärmprotokoll: Test", AlertMessages.titel(AlertKind.TEST))
    }
}
