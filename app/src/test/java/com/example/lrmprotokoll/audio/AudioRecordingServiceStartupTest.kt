package com.example.lrmprotokoll.audio

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.lrmprotokoll.LaermprotokollApp
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
    fun totmannschaltungWirdOhneAlarmierungFuerBeideQuellenGeplant() {
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        val settings = app.container.settingsManager
        org.robolectric.Shadows
            .shadowOf(app)
            .grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        try {
            for (quelle in listOf("MIKROFON", "PCE_323")) {
                settings.audioTriggerQuelle = quelle
                settings.meterDeviceAddress = null
                settings.alarmierungAktiv = false
                settings.heartbeatUrl = "https://hc-ping.com/test-geheim"
                val intent = Intent(app, AudioRecordingService::class.java)
                val service = Robolectric.buildService(AudioRecordingService::class.java, intent)
                service.create().startCommand(0, 1)
                assertTrue("Dienst muss bei $quelle laufen", AudioRecordingService.laeuft.value)
                val infos = WorkManager.getInstance(app).getWorkInfosForUniqueWork("heartbeat").get()
                assertTrue("Heartbeat muss bei $quelle geplant sein", infos.any { !it.state.isFinished })
                service.get().onStartCommand(
                    Intent(app, AudioRecordingService::class.java).apply {
                        action = ACTION_STOP_SERVICE
                    },
                    0,
                    2,
                )
                val nachStopp = WorkManager.getInstance(app).getWorkInfosForUniqueWork("heartbeat").get()
                assertTrue("Heartbeat muss nach ausdruecklichem Stopp beendet sein", nachStopp.all { it.state.isFinished })
                service.destroy()
            }
        } finally {
            settings.heartbeatUrl = ""
            settings.monitoringWasActive = false
            WorkManagerTestInitHelper.closeWorkDatabase()
        }
    }

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

    @Test
    fun serviceStartOhneMessgeraetUndOhneMikrofonberechtigungSetztAufzeichnungshinweisUndStoppt() {
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        app.container.settingsManager.meterDeviceAddress = null
        val shadowApp = org.robolectric.Shadows.shadowOf(app)
        shadowApp.denyPermissions(android.Manifest.permission.RECORD_AUDIO)

        AudioRecordingService.testSetzeAufzeichnungsHinweis(null)
        AudioRecordingService.testSetzeLaeuft(false)

        val intent = Intent(app, AudioRecordingService::class.java)
        val serviceController = Robolectric.buildService(AudioRecordingService::class.java, intent)
        serviceController.create()
        serviceController.startCommand(0, 1)

        org.junit.Assert.assertFalse(AudioRecordingService.laeuft.value)
        org.junit.Assert.assertNotNull(AudioRecordingService.aufzeichnungsHinweis.value)
        org.junit.Assert.assertEquals(
            "Kein Messgerät gekoppelt und Mikrofonberechtigung fehlt.",
            AudioRecordingService.aufzeichnungsHinweis.value,
        )

        // Nach dem Test bereinigen
        AudioRecordingService.testSetzeAufzeichnungsHinweis(null)
        serviceController.destroy()
    }
}
