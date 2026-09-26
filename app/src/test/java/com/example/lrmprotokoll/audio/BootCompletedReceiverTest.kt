package com.example.lrmprotokoll.audio

import android.Manifest
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
        // SettingsManager haelt prozessweiten Zustand - sonst traegt ein vorheriger Test eine
        // gepinnte Geraeteadresse in den naechsten (Prueffpunkt 3 im Flakiness-Bericht).
        app.container.settingsManager.meterDeviceAddress = null
        // Robolectric verweigert Laufzeitberechtigungen per Voreinstellung. Seit F-35 startet der
        // Receiver nur noch, wenn eine Quelle bereitsteht - die Faelle, die den Start pruefen,
        // brauchen die Mikrofonberechtigung deshalb ausdruecklich.
        shadowOf(app as Application).grantPermissions(Manifest.permission.RECORD_AUDIO)
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

    /**
     * F-35: Zwischen dem letzten Lauf und dem Neustart kann die Mikrofonberechtigung entzogen und
     * das Messgeraet entkoppelt worden sein. Der Dienst bekaeme dann keinen erlaubten
     * Foreground-Typ und wuerde sich sofort wieder beenden. Ein Nutzer, dem man das sagen koennte,
     * ist beim Boot nicht da - also gar nicht erst starten.
     */
    @Test
    fun ohneMikrofonberechtigungUndOhneMessgeraetWirdNachDemNeustartNichtsGestartet() {
        app.container.settingsManager.monitoringWasActive = true
        app.container.settingsManager.audioMonitoringWasActive = true
        app.container.settingsManager.meterDeviceAddress = null
        shadowOf(app as Application).denyPermissions(Manifest.permission.RECORD_AUDIO)

        receiver.onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertNull(
            "Ohne Mikrofonberechtigung und ohne gekoppeltes Messgeraet haette der Dienst nichts " +
                "zu ueberwachen - ein Start endete im sofortigen Selbststopp (F-35)",
            shadowOf(app as Application).nextStartedService,
        )
    }

    @Test
    fun ohneMikrofonberechtigungAberMitGekoppeltemMessgeraetWirdGestartet() {
        app.container.settingsManager.monitoringWasActive = true
        app.container.settingsManager.audioMonitoringWasActive = false
        app.container.settingsManager.meterDeviceAddress = "AA:BB:CC:DD:EE:FF"
        shadowOf(app as Application).denyPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(app as Application).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)

        receiver.onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertEquals(
            "Ein gekoppeltes Messgeraet allein reicht fuer den connectedDevice-Typ - der Neustart " +
                "muss die Ueberwachung dann wieder aufnehmen",
            AudioRecordingService::class.java.name,
            shadowOf(app as Application).nextStartedService?.component?.className,
        )
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
