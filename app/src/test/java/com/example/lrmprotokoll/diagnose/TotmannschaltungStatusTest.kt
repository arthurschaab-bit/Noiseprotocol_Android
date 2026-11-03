package com.example.lrmprotokoll.diagnose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-Tests für Schritt 8 aus `docs/PROMPT_DIAGNOSE_STILLSTAND.md`:
 * Reine Funktion [TotmannschaltungStatus.bewerten] für alle vier Zustände, die 45-Minuten-Grenze
 * und den ausgeschalteten Aufzeichnungszustand.
 */
class TotmannschaltungStatusTest {
    private val basisZeit = 1_760_000_000_000L
    private val fuenfundvierzigMinMs = 45L * 60_000L

    @Test
    fun aufnahmeAusLiefertImmerOkUnabhaengigVonUrlUndJob() {
        assertEquals(
            TotmannschaltungZustand.OK,
            TotmannschaltungStatus.bewerten(
                aufnahmeLaeuft = false,
                konfiguriert = false,
                geplant = false,
                letzterErfolgAt = 0L,
                jetztMs = basisZeit,
            ),
        )
        assertEquals(
            TotmannschaltungZustand.OK,
            TotmannschaltungStatus.bewerten(
                aufnahmeLaeuft = false,
                konfiguriert = true,
                geplant = false,
                letzterErfolgAt = 0L,
                jetztMs = basisZeit,
            ),
        )
    }

    @Test
    fun aufnahmeAktivOhneGueltigeUrlLiefertNichtEingerichtet() {
        val zustand =
            TotmannschaltungStatus.bewertenMitUrl(
                aufnahmeLaeuft = true,
                heartbeatUrl = "",
                geplant = false,
                letzterErfolgAt = 0L,
                jetztMs = basisZeit,
            )
        assertEquals(TotmannschaltungZustand.NICHT_EINGERICHTET, zustand)
        assertTrue(TotmannschaltungStatus.zeigeImHauptbildschirm(zustand))
        assertTrue(TotmannschaltungStatus.zeigeImDiagnoseBildschirm(zustand))
    }

    @Test
    fun aufnahmeAktivMitUngueltigerHttpUrlLiefertNichtEingerichtet() {
        val zustand =
            TotmannschaltungStatus.bewertenMitUrl(
                aufnahmeLaeuft = true,
                heartbeatUrl = "http://hc-ping.com/ungueltig-kein-https",
                geplant = true,
                letzterErfolgAt = basisZeit,
                jetztMs = basisZeit,
            )
        assertEquals(TotmannschaltungZustand.NICHT_EINGERICHTET, zustand)
    }

    @Test
    fun aufnahmeAktivUndUrlGueltigAberJobNichtGeplantLiefertNichtGeplant() {
        val zustand =
            TotmannschaltungStatus.bewertenMitUrl(
                aufnahmeLaeuft = true,
                heartbeatUrl = "https://hc-ping.com/test-uuid",
                geplant = false,
                letzterErfolgAt = basisZeit - 5 * 60_000L,
                jetztMs = basisZeit,
            )
        assertEquals(TotmannschaltungZustand.NICHT_GEPLANT, zustand)
        assertTrue(TotmannschaltungStatus.zeigeImHauptbildschirm(zustand))
        assertTrue(TotmannschaltungStatus.zeigeImDiagnoseBildschirm(zustand))
    }

    @Test
    fun geplantUndLetzterErfolgGenau45MinutenAltLiefertOk() {
        val zustand =
            TotmannschaltungStatus.bewerten(
                aufnahmeLaeuft = true,
                konfiguriert = true,
                geplant = true,
                letzterErfolgAt = basisZeit - fuenfundvierzigMinMs,
                jetztMs = basisZeit,
            )
        assertEquals(TotmannschaltungZustand.OK, zustand)
        assertFalse(TotmannschaltungStatus.zeigeImHauptbildschirm(zustand))
        assertFalse(TotmannschaltungStatus.zeigeImDiagnoseBildschirm(zustand))
    }

    @Test
    fun geplantUndLetzterErfolgAelterAls45MinutenLiefertSendetNicht() {
        val zustand =
            TotmannschaltungStatus.bewerten(
                aufnahmeLaeuft = true,
                konfiguriert = true,
                geplant = true,
                letzterErfolgAt = basisZeit - fuenfundvierzigMinMs - 1L,
                jetztMs = basisZeit,
            )
        assertEquals(TotmannschaltungZustand.SENDET_NICHT, zustand)
        assertFalse(TotmannschaltungStatus.zeigeImHauptbildschirm(zustand))
        assertTrue(TotmannschaltungStatus.zeigeImDiagnoseBildschirm(zustand))
    }

    @Test
    fun geplantOhneBisherigenErfolgLiefertSendetNicht() {
        val zustand =
            TotmannschaltungStatus.bewerten(
                aufnahmeLaeuft = true,
                konfiguriert = true,
                geplant = true,
                letzterErfolgAt = 0L,
                jetztMs = basisZeit,
            )
        assertEquals(TotmannschaltungZustand.SENDET_NICHT, zustand)
    }
}
