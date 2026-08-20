package com.example.lrmprotokoll.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Owner-Rückmeldung: "'Mit Google verbinden' kommt nur 'Verbindung fehlgeschlagen: Kein
 * Zugriffstoken verfügbar'." - die eigentliche Ursache (fehlende OAuth-Client-ID) stand nur in
 * der `cause`-Kette der Exception, wurde der UI aber vorenthalten. Reiner JVM-Test, kein
 * Robolectric nötig.
 */
class FormatiereDriveFehlerTest {

    @Test
    fun ohneUrsacheBleibtNurDieHauptmeldung() {
        val meldung = formatiereDriveFehler(RuntimeException("Kein Zugriffstoken verfügbar"))
        assertEquals("Verbindung fehlgeschlagen: Kein Zugriffstoken verfügbar", meldung)
    }

    @Test
    fun mitUrsacheWirdSieAngehaengtStattVerschluckt() {
        val ursache = IllegalStateException("Keine echte OAuth-Client-ID eingerichtet - siehe GoogleClientConfig")
        val fehler = RuntimeException("Kein Zugriffstoken verfügbar", ursache)

        val meldung = formatiereDriveFehler(fehler)

        assertEquals(
            "Verbindung fehlgeschlagen: Kein Zugriffstoken verfügbar " +
                "(Keine echte OAuth-Client-ID eingerichtet - siehe GoogleClientConfig)",
            meldung,
        )
    }
}
