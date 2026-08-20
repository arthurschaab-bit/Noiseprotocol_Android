package com.example.lrmprotokoll.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Reine JVM-Tests fuer [wiedergabeFehlermeldung] - kein Robolectric noetig. Testplan-Befund
 * (docs/TESTPLAN_INSTRUMENTIERT.md): `AudioPlayerScreen`s `DisposableEffect` fing
 * `mediaPlayer.setDataSource()`/`.prepare()` bislang nicht ab - eine zwischenzeitlich gelöschte
 * oder beschädigte Audiodatei ließ den Screen mit einer unbehandelten `IOException` abstürzen.
 * Dieser Test deckt nur die Fehlermeldungs-Ableitung ab - dass die Exception im Compose-Code
 * tatsächlich abgefangen wird (statt nur formatiert), ist per Code-Review belegt: ein echter
 * `MediaPlayer`-Fehlschlag lässt sich unter Robolectric (Shadow-Implementierung ohne echte
 * Dekodierung) nicht auslösen, siehe docs/TESTPLAN_INSTRUMENTIERT.md für den vorgesehenen
 * Emulator-Test.
 */
class AudioPlayerScreenLogicTest {

    @Test
    fun ioExceptionErgibtVerstaendlicheDateiMeldung() {
        val meldung = wiedergabeFehlermeldung(IOException("setDataSourceFD failed: status=0x80000000"))
        assertTrue(meldung.contains("gelöscht"))
        assertTrue(meldung.contains("beschädigt"))
    }

    @Test
    fun sonstigeExceptionZeigtDieUrspruenglicheMeldung() {
        val meldung = wiedergabeFehlermeldung(IllegalStateException("prepare() called in state 0"))
        assertEquals("Wiedergabe fehlgeschlagen: prepare() called in state 0", meldung)
    }

    @Test
    fun exceptionOhneMessageFaelltAufDenKlassennamenZurueck() {
        val meldung = wiedergabeFehlermeldung(object : RuntimeException() {
            override val message: String? = null
        })
        assertTrue(meldung.startsWith("Wiedergabe fehlgeschlagen: "))
        assertTrue(meldung != "Wiedergabe fehlgeschlagen: ")
    }
}
