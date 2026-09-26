package com.example.lrmprotokoll.audio

import android.Manifest
import android.app.Application
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * F-35: Der Dienst bekommt nur dann einen erlaubten Foreground-Typ, wenn mindestens eine Quelle
 * bereitsteht. Fehlt beides, darf er gar nicht erst gestartet werden - sonst startet er, scheitert
 * und beendet sich selbst, ohne dass der Nutzer erfaehrt warum.
 *
 * Robolectric verweigert Laufzeitberechtigungen per Voreinstellung. Die Faelle MIT Berechtigung
 * erteilen sie deshalb ausdruecklich; der Grenzfall ohne beides ist der Robolectric-Grundzustand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DienstvoraussetzungenTest {
    private lateinit var app: LaermprotokollApp

    @Before
    fun aufbauen() {
        app = ApplicationProvider.getApplicationContext()
        app.container.settingsManager.meterDeviceAddress = null
    }

    private fun erteileMikrofon() = shadowOf(app as Application).grantPermissions(Manifest.permission.RECORD_AUDIO)

    private fun erteileBluetooth() = shadowOf(app as Application).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)

    @Test
    fun mitMikrofonberechtigungGiltDerMikrofontyp() {
        erteileMikrofon()

        val typ = berechneForegroundServiceType(app, app.container.settingsManager)

        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, typ)
        assertTrue(kannDienstInDenVordergrund(app, app.container.settingsManager))
    }

    @Test
    fun ohneMikrofonAberMitGekoppeltemGeraetGiltDerGeraetetyp() {
        erteileBluetooth()
        app.container.settingsManager.meterDeviceAddress = "AA:BB:CC:DD:EE:FF"

        val typ = berechneForegroundServiceType(app, app.container.settingsManager)

        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, typ)
        assertTrue(kannDienstInDenVordergrund(app, app.container.settingsManager))
    }

    @Test
    fun mitBeidemGeltenBeideTypen() {
        erteileMikrofon()
        erteileBluetooth()
        app.container.settingsManager.meterDeviceAddress = "AA:BB:CC:DD:EE:FF"

        val typ = berechneForegroundServiceType(app, app.container.settingsManager)

        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            typ,
        )
    }

    @Test
    fun ohneMikrofonUndOhneGeraetBleibtDerTypLeer() {
        val typ = berechneForegroundServiceType(app, app.container.settingsManager)

        assertEquals(0, typ)
        assertFalse(
            "Ohne Mikrofonberechtigung und ohne gekoppeltes Messgeraet darf der Dienst nicht " +
                "gestartet werden - er kaeme nicht in den Vordergrund (F-35)",
            kannDienstInDenVordergrund(app, app.container.settingsManager),
        )
    }

    @Test
    fun einGepinntesGeraetOhneBluetoothBerechtigungZaehltNicht() {
        app.container.settingsManager.meterDeviceAddress = "AA:BB:CC:DD:EE:FF"

        assertEquals(0, berechneForegroundServiceType(app, app.container.settingsManager))
        assertFalse(
            "Eine gespeicherte Geraeteadresse ohne BLUETOOTH_CONNECT ergibt keinen erlaubten " +
                "connectedDevice-Typ",
            kannDienstInDenVordergrund(app, app.container.settingsManager),
        )
    }
}
