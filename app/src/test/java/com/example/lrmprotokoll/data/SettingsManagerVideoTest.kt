package com.example.lrmprotokoll.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Die Video-Einstellungen aus M11 Etappe B. Getestet wird nicht "ein Setter setzt", sondern
 * genau das, was schiefgehen kann: der Default des Drive-Uploads und die Begrenzung der
 * Maximaldauer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsManagerVideoTest {

    private fun settings() = SettingsManager(ApplicationProvider.getApplicationContext())

    @Test
    fun videoUploadIstStandardmaessigAn() {
        // Owner-Entscheidung nach Datenverlust durch Deinstallation (09.09.2026): wie
        // driveUploadWav und fotoDokuDriveUpload standardmaessig AN, trotz des Datenschutz-
        // Risikos, das der urspruengliche Default-AUS-Zustand vermeiden sollte.
        assertTrue(settings().videoDriveUpload)
    }

    @Test
    fun videoUploadKannDeaktiviertWerden() {
        settings().videoDriveUpload = false
        assertEquals(false, settings().videoDriveUpload)
    }

    @Test
    fun maximaldauerHatDreiMinutenAlsDefault() {
        assertEquals(180, settings().videoMaxDauerSekunden)
    }

    @Test
    fun maximaldauerWirdInBeideRichtungenBegrenzt() {
        // Ein 0-Wert wuerde jede Aufnahme sofort abwuergen, ein sehr grosser die Drift- und
        // Speichergrenze aushebeln.
        settings().videoMaxDauerSekunden = 0
        assertEquals(10, settings().videoMaxDauerSekunden)

        settings().videoMaxDauerSekunden = 99_999
        assertEquals(900, settings().videoMaxDauerSekunden)
    }

    @Test
    fun aufloesungIstStandardmaessig720p() {
        assertEquals("HD", settings().videoAufloesung)

        settings().videoAufloesung = "FHD"
        assertEquals("FHD", settings().videoAufloesung)
    }
}
