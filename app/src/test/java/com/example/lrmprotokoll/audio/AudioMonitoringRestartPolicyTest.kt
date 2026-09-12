package com.example.lrmprotokoll.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bugfix (Support-Bundle-Auswertung 12.09.2026, Ausfall 0:10 bis 3:51 Uhr): das
 * Diagnoseprotokoll zeigte, dass ein AUDIO_FILE_WRITE_FAILED (EBADF) zu stopSelf() fuehrte, und
 * der Dienst danach - entgegen dem Kommentar im alten Code - NICHT automatisch neu gestartet
 * wurde. START_STICKY greift nur bei einem System-Kill, nicht bei stopSelf(). Dieser Test prueft
 * die reine Ersatz-Entscheidungslogik [entscheideUeberAudioMonitoringRestart].
 */
class AudioMonitoringRestartPolicyTest {

    @Test
    fun ersterFehlerVersuchtSofortEinenNeustart() {
        val entscheidung = entscheideUeberAudioMonitoringRestart(AudioMonitoringRestartZustand(), jetzt = 1_000L)
        assertTrue(entscheidung.neustartVersuchen)
        assertEquals(1, entscheidung.neuerZustand.versuche)
    }

    @Test
    fun zaehltInnerhalbDesFenstersHoch() {
        var zustand = AudioMonitoringRestartZustand()
        var jetzt = 0L
        repeat(MAX_AUDIO_MONITORING_RESTART_VERSUCHE) {
            jetzt += 1_000L
            val entscheidung = entscheideUeberAudioMonitoringRestart(zustand, jetzt)
            assertTrue("Versuch ${it + 1} sollte noch erlaubt sein", entscheidung.neustartVersuchen)
            zustand = entscheidung.neuerZustand
        }
        assertEquals(MAX_AUDIO_MONITORING_RESTART_VERSUCHE, zustand.versuche)
    }

    @Test
    fun gibtNachDerObergrenzeAufUndSpinntNichtEndlos() {
        var zustand = AudioMonitoringRestartZustand()
        var jetzt = 0L
        repeat(MAX_AUDIO_MONITORING_RESTART_VERSUCHE) {
            jetzt += 1_000L
            zustand = entscheideUeberAudioMonitoringRestart(zustand, jetzt).neuerZustand
        }

        jetzt += 1_000L
        val entscheidung = entscheideUeberAudioMonitoringRestart(zustand, jetzt)

        assertFalse("Nach $MAX_AUDIO_MONITORING_RESTART_VERSUCHE Versuchen darf kein weiterer Neustart mehr versucht werden", entscheidung.neustartVersuchen)
    }

    @Test
    fun setztDenZaehlerNachEinerRuhigenMinuteZurueck() {
        var zustand = AudioMonitoringRestartZustand()
        var jetzt = 0L
        repeat(MAX_AUDIO_MONITORING_RESTART_VERSUCHE) {
            jetzt += 1_000L
            zustand = entscheideUeberAudioMonitoringRestart(zustand, jetzt).neuerZustand
        }

        // Fenster abgelaufen (> AUDIO_MONITORING_RESTART_FENSTER_MS seit dem letzten Versuch) -
        // ein neuer, spaeter auftretender Fehler ist ein frisches Problem, kein fortgesetztes.
        val jetztNachPause = jetzt + AUDIO_MONITORING_RESTART_FENSTER_MS + 1L
        val entscheidung = entscheideUeberAudioMonitoringRestart(zustand, jetztNachPause)

        assertTrue(entscheidung.neustartVersuchen)
        assertEquals(1, entscheidung.neuerZustand.versuche)
    }
}
