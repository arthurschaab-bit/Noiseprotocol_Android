package com.example.lrmprotokoll.testhilfen

import androidx.compose.runtime.mutableIntStateOf
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Nachweis fuer [GlobaleSchreiber] (Untersuchung AppNotIdle, PR #280). Reines JVM, ohne Robolectric:
 * die Compose-Runtime meldet globale Schreibzugriffe auch dort an die registrierten Beobachter.
 * Der Thread `SDK 99 Main Thread` ahmt den Haupt-Thread nach - der Beobachter erkennt ihn am Namen.
 */
class GlobaleSchreiberTest {
    @Before
    fun vorbereiten() {
        GlobaleSchreiber.installiere()
        GlobaleSchreiber.zuruecksetzen()
    }

    @Test
    fun einSchreibzugriffAusEinemFremdenThreadWirdMitNamePhaseUndStackErfasst() {
        GlobaleSchreiber.setzePhase("Phase-Fremd")
        val zustand = mutableIntStateOf(0)

        schreibeAusThread("fremd-test-1") { zustand.intValue = 1 }

        val text = GlobaleSchreiber.text()
        assertTrue("Threadname fehlt: $text", text.contains("fremd-test-1"))
        assertTrue("Phase fehlt: $text", text.contains("[Phase-Fremd]"))
        assertTrue("Stack fehlt: $text", text.contains("GlobaleSchreiberTest"))
    }

    @Test
    fun einSchreibzugriffDesMainThreadsWirdNurGezaehltUndNichtAufgezeichnet() {
        GlobaleSchreiber.setzePhase("Phase-Main")
        val zustand = mutableIntStateOf(0)

        schreibeAusThread("SDK 99 Main Thread") { zustand.intValue = 1 }

        val text = GlobaleSchreiber.text()
        assertTrue("Zaehler fehlt: $text", Regex("""1 SDK 99 Main Thread""").containsMatchIn(text))
        assertTrue("Main-Thread darf nicht aufgezeichnet sein: $text", text.contains("kein Schreibzugriff aus einem anderen Thread"))
    }

    private fun schreibeAusThread(
        name: String,
        block: () -> Unit,
    ) {
        val thread = Thread(block, name)
        thread.start()
        thread.join()
    }
}
