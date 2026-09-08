package com.example.lrmprotokoll.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioStartPolicyTest {

    @Test
    fun expliziterStartStartetAudio() {
        assertTrue(sollAudioMonitoringStarten(expliziterStart = true, audioWarVorherAktiv = false))
    }

    @Test
    fun serviceRecreateNimmtVorherAktiveAudioaufnahmeWiederAuf() {
        assertTrue(sollAudioMonitoringStarten(expliziterStart = false, audioWarVorherAktiv = true))
    }

    @Test
    fun reinerMeterStartAktiviertNieZuvorGestopptesAudioNichtNeu() {
        assertFalse(sollAudioMonitoringStarten(expliziterStart = false, audioWarVorherAktiv = false))
    }
}
