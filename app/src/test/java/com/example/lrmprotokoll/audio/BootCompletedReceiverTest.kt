package com.example.lrmprotokoll.audio

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Testluecken-Auftrag Stufe 3: [BootCompletedReceiver] nimmt die Ueberwachung nach einem
 * Geraeteneustart automatisch wieder auf (Plan 5.4) - aber nur, wenn sie beim letzten expliziten
 * Stop noch aktiv war. Der interessante Teil ist deshalb weniger "reagiert er auf
 * BOOT_COMPLETED", sondern "reagiert er NICHT, wenn monitoringWasActive false ist" - sonst
 * wuerde jeder Neustart die Ueberwachung starten, auch wenn der Nutzer sie zuletzt bewusst
 * ausgeschaltet hatte.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BootCompletedReceiverTest {

    private lateinit var app: LaermprotokollApp
    private lateinit var receiver: BootCompletedReceiver

    @Before
    fun aufbauen() {
        app = ApplicationProvider.getApplicationContext()
        receiver = BootCompletedReceiver()
        shadowOf(app as Application).clearStartedServices()
    }

    @Test
    fun beiAktiverUeberwachungWirdDerDienstNachDemNeustartWiederGestartet() {
        app.container.settingsManager.monitoringWasActive = true
        app.container.settingsManager.audioMonitoringWasActive = true

        receiver.onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))

        val gestarteterIntent = shadowOf(app as Application).nextStartedService
        assertEquals(AudioRecordingService::class.java.name, gestarteterIntent?.component?.className)
        assertEquals(true, gestarteterIntent?.getBooleanExtra(EXTRA_START_AUDIO_MONITORING, false))
    }

    @Test
    fun ohneVorherAktiveUeberwachungWirdNichtsGestartet() {
        app.container.settingsManager.monitoringWasActive = false

        receiver.onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertNull(
            "Ein Neustart darf die Ueberwachung nicht starten, wenn der Nutzer sie zuletzt bewusst " +
                "ausgeschaltet hatte",
            shadowOf(app as Application).nextStartedService,
        )
    }

    @Test
    fun eineAndereActionWirdIgnoriert() {
        app.container.settingsManager.monitoringWasActive = true

        receiver.onReceive(app, Intent(Intent.ACTION_LOCALE_CHANGED))

        assertNull(shadowOf(app as Application).nextStartedService)
    }

    @Test
    fun gibtDenZuletztAktivenMikrofonstatusAlsExtraWeiterNichtImmerTrue() {
        app.container.settingsManager.monitoringWasActive = true
        app.container.settingsManager.audioMonitoringWasActive = false

        receiver.onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))

        val gestarteterIntent = shadowOf(app as Application).nextStartedService
        assertEquals(
            "Messgeraet-Ueberwachung ohne Mikrofon-Monitoring darf nach dem Neustart nicht " +
                "ungefragt das Mikrofon mit hochfahren",
            false, gestarteterIntent?.getBooleanExtra(EXTRA_START_AUDIO_MONITORING, true),
        )
    }
}
