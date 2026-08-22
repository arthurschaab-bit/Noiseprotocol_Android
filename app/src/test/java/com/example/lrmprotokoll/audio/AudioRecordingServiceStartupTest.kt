package com.example.lrmprotokoll.audio

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regressionstest für den Start des [AudioRecordingService] ohne Bluetooth-Verbindung.
 *
 * Verifiziert, dass:
 * 1. Der Dienst ohne gekoppeltes Bluetooth-Messgerät und ohne BLUETOOTH_CONNECT-Berechtigung
 *    fehlerfrei im Foreground-Modus starten kann.
 * 2. Keine ungefangene SecurityException ausgelöst wird, wenn CONNECTED_DEVICE-Typen
 *    nur dynamisch bei erteilten Berechtigungen verwendet werden.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioRecordingServiceStartupTest {

    @Test
    fun serviceStartetOhneBluetoothVerbindungOhneAbsturz() {
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        app.container.settingsManager.meterDeviceAddress = null

        val intent = Intent(app, AudioRecordingService::class.java).apply {
            putExtra(EXTRA_START_AUDIO_MONITORING, true)
        }

        val serviceController = Robolectric.buildService(AudioRecordingService::class.java, intent)
        serviceController.create()
        serviceController.startCommand(0, 1)

        val service = serviceController.get()
        assertNotNull(service)
        serviceController.destroy()
    }

    @Test
    fun stopAudioRecordingActionSetztAudioAufnahmeAktivAufFalse() {
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()

        val startIntent = Intent(app, AudioRecordingService::class.java).apply {
            putExtra(EXTRA_START_AUDIO_MONITORING, true)
        }
        val serviceController = Robolectric.buildService(AudioRecordingService::class.java, startIntent)
        serviceController.create()
        serviceController.startCommand(0, 1)

        // Stop Audio Recording
        val stopAudioIntent = Intent(app, AudioRecordingService::class.java).apply {
            action = ACTION_STOP_AUDIO_RECORDING
        }
        serviceController.get().onStartCommand(stopAudioIntent, 0, 2)

        org.junit.Assert.assertFalse(AudioRecordingService.audioAufnahmeAktiv.value)

        serviceController.destroy()
    }
}
