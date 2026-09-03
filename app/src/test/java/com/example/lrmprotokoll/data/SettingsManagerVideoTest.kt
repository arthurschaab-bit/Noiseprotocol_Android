package com.example.lrmprotokoll.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Die Video-Einstellungen aus M11 Etappe B. Getestet wird nicht "ein Setter setzt", sondern
 * genau das, was schiefgehen kann: der bewusst abweichende Default des Drive-Uploads und die
 * Begrenzung der Maximaldauer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsManagerVideoTest {

    private fun settings() = SettingsManager(ApplicationProvider.getApplicationContext())

    @Test
    fun videoUploadIstStandardmaessigAus() {
        // Bewusst anders als driveUploadWav und fotoDokuDriveUpload: Ein Video ist die
        // datenschutzsensibelste Datenart der App.
        assertFalse(settings().videoDriveUpload)
    }

    @Test
    fun videoUploadUeberlebtEineNeueInstanz() {
        settings().videoDriveUpload = true
        assertEquals(true, settings().videoDriveUpload)
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
