package com.example.lrmprotokoll.messreihe

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Isoliert ermittleSpeicherplatz() von SettingsScreen/AnimatedVisibility/LaunchedEffect: auf CI
 * (PR #150, instrumented-tests) haengt speicherplatzAbschnittZeigtErmittelteGroessenNachDemAufklappen
 * konstant nach 15s, obwohl das vorgelagerte Aufklappen selbst nachweislich funktioniert (der
 * Zwischen-waitUntil auf den zweiten Titel-Treffer geht durch) - dieser Test prueft direkt, ob die
 * Funktion selbst auf echtem Geraet/Emulator schnell zurueckkommt.
 */
@RunWith(AndroidJUnit4::class)
class SpeicherplatzUebersichtInstrumentedTest {

    @Test
    fun ermittleSpeicherplatzKommtInnerhalbVonZweiSekundenZurueck() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val start = System.currentTimeMillis()
        val ergebnis = ermittleSpeicherplatz(context)
        val dauerMs = System.currentTimeMillis() - start

        assertTrue("audioBytes darf nicht negativ sein: $ergebnis", ergebnis.audioBytes >= 0L)
        assertTrue("datenbankBytes darf nicht negativ sein: $ergebnis", ergebnis.datenbankBytes >= 0L)
        assertTrue(
            "ermittleSpeicherplatz() sollte reine Datei-Metadaten-Operationen sein und in unter " +
                "2s zurueckkommen, brauchte aber ${dauerMs}ms",
            dauerMs < 2_000L,
        )
    }
}
