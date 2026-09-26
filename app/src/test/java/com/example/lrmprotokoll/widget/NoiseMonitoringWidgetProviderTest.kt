package com.example.lrmprotokoll.widget

import android.Manifest
import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.audio.AudioRecordingService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * F-35 auf der Aufruferseite: Das Homescreen-Widget hat keine eigene Berechtigungspruefung. Ohne
 * Mikrofonberechtigung und ohne gekoppeltes Messgeraet bekaeme der Dienst keinen erlaubten
 * Foreground-Typ - er wuerde starten und sich sofort wieder beenden, der Tipp bliebe folgenlos.
 *
 * Robolectric verweigert Laufzeitberechtigungen per Voreinstellung; der Fall MIT Berechtigung
 * erteilt sie deshalb ausdruecklich.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoiseMonitoringWidgetProviderTest {
    private lateinit var app: LaermprotokollApp
    private lateinit var provider: NoiseMonitoringWidgetProvider

    @Before
    fun aufbauen() {
        app = ApplicationProvider.getApplicationContext()
        provider = NoiseMonitoringWidgetProvider()
        app.container.settingsManager.meterDeviceAddress = null
        shadowOf(app as Application).clearStartedServices()
    }

    @Test
    fun ohneQuelleStartetDasWidgetDenDienstNicht() {
        provider.onReceive(app, Intent(ACTION_TOGGLE))

        assertNull(
            "Ohne Mikrofonberechtigung und ohne gekoppeltes Messgeraet darf das Widget den Dienst " +
                "nicht starten - er kaeme nicht in den Vordergrund (F-35)",
            shadowOf(app as Application).nextStartedService,
        )
    }

    @Test
    fun mitMikrofonberechtigungStartetDasWidgetDenDienst() {
        shadowOf(app as Application).grantPermissions(Manifest.permission.RECORD_AUDIO)

        provider.onReceive(app, Intent(ACTION_TOGGLE))

        assertEquals(
            AudioRecordingService::class.java.name,
            shadowOf(app as Application).nextStartedService?.component?.className,
        )
    }

    @Test
    fun eineAndereActionWirdIgnoriert() {
        shadowOf(app as Application).grantPermissions(Manifest.permission.RECORD_AUDIO)

        provider.onReceive(app, Intent(Intent.ACTION_LOCALE_CHANGED))

        assertNull(shadowOf(app as Application).nextStartedService)
    }
}
