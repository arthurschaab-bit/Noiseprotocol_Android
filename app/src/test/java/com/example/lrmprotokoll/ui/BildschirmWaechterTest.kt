package com.example.lrmprotokoll.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Hat der Nutzer den Videoscreen waehrend der Aufnahme verlassen (Zurueck-Taste: onDispose stoppt
 * die Aufnahme, Finalize kommt danach), darf der Abschluss nicht noch einmal zurueck navigieren -
 * sonst schlosse onBack() den Screen, auf dem der Nutzer inzwischen ist.
 */
class BildschirmWaechterTest {
    @Test
    fun fuehrtDenBlockAusSolangeDerScreenDaIst() {
        val waechter = BildschirmWaechter()
        var aufrufe = 0

        waechter.wennAktiv { aufrufe++ }

        assertEquals(1, aufrufe)
    }

    @Test
    fun fuehrtDenBlockNachDemVerlassenNichtMehrAus() {
        val waechter = BildschirmWaechter()
        var aufrufe = 0

        waechter.verlassen()
        waechter.wennAktiv { aufrufe++ }

        assertEquals(0, aufrufe)
    }
}
