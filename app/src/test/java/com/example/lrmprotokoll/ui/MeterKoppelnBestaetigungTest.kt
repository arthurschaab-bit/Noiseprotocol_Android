package com.example.lrmprotokoll.ui

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.AppContainer
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.meter.FakeMeterTransport
import com.example.lrmprotokoll.meter.ble.BleDevice
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Geraetetest 30.09.2026: Der Owner sah im Messgeraet-Screen einen **Bose Revolve** in der
 * Geraeteliste. Ein einziger Tipp genuegte bisher, um irgendein BLE-Geraet als Messgeraet zu
 * pinnen - [MeterScreen] rief direkt `pinne(device)` und damit `ensureConnected()`. Im
 * Support-Bundle steht die Folge fuenfzehnmal: "Kein Frame innerhalb von 5000ms nach
 * Verbindungsaufbau - Versuch verworfen".
 *
 * **Das ist ausdruecklich kein M6-Fall.** Das Bedrohungsmodell aus Plan Abschnitt 6 ist Spoofing
 * - ein fremdes Geraet, das sich als das gepinnte ausgibt. Dagegen greift
 * [com.example.lrmprotokoll.meter.GeraetePinning] mit `VERDAECHTIG_GLEICHER_NAME`, und dieser
 * Pfad bleibt unveraendert. Ein versehentlich getippter Lautsprecher ist kein Angriff, sondern
 * ein Bedienfehler, den die App widerspruchslos uebernommen hat.
 *
 * **Warum nicht gefiltert wird:** `BleScanner` filtert bewusst nicht nach dem Custom-Service
 * `0000fff0` - ob das PCE-323 ihn ueberhaupt bewirbt, ist unbekannt (docs/PROTOKOLL_PCE-323.md).
 * Ein Filter, der das echte Messgeraet versteckt, waere schlimmer als eine volle Liste. Deshalb
 * Reibung statt Filter: ein Bestaetigungsschritt, der niemanden aussperrt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeterKoppelnBestaetigungTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: LaermprotokollApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        // Ohne FakeMeterTransport benutzt MeterScreen den produktiven Container und loest einen
        // echten BLE-Scan aus - dieselbe Falle, die in MeterScreenPermissionAndScanTest
        // dokumentiert ist (Issue #160).
        app.setCustomContainer(AppContainer(app, FakeMeterTransport(), bleScanProviderOverride = { flowOf(FREMDES_GERAET) }))
        shadowOf(app).grantPermissions(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
        app.container.settingsManager.meterDeviceAddress = null
        app.container.settingsManager.meterDeviceName = null
    }

    @After
    fun tearDown() {
        app.container.settingsManager.meterDeviceAddress = null
        app.container.settingsManager.meterDeviceName = null
        app.resetContainer()
    }

    /** Der Kern des Befunds: ein Tipp allein pinnt nicht mehr. */
    @Test
    fun einTippAufEinGefundenesGeraetKoppeltNochNicht() {
        zeigeListeMitFremdemGeraet()

        composeRule.onNodeWithTag(KARTE).performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("dialog_pair_confirm").assertIsDisplayed()
        assertNull(
            "Ein Tipp darf noch nichts gepinnt haben",
            app.container.settingsManager.meterDeviceAddress,
        )
        // Review-Befund 01.10.2026: pinne() schreibt BEIDE Felder, also beide pruefen - sonst
        // bliebe ein halb geschriebener Zustand unentdeckt.
        assertNull(
            "Auch der Name darf noch nicht gesetzt sein",
            app.container.settingsManager.meterDeviceName,
        )
    }

    /** Die Bestaetigung pinnt - sonst waere der Dialog eine Sackgasse. */
    @Test
    fun erstDieBestaetigungKoppelt() {
        zeigeListeMitFremdemGeraet()

        composeRule.onNodeWithTag(KARTE).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("dialog_pair_confirm").performClick()
        composeRule.waitForIdle()

        assertEquals(FREMDES_GERAET.address, app.container.settingsManager.meterDeviceAddress)
        // pinne() setzt den Namen auf `device.name ?: device.address`; hier ist er gesetzt.
        assertEquals(FREMDES_GERAET.name, app.container.settingsManager.meterDeviceName)
    }

    /** Abbrechen laesst alles, wie es war - auch den Dialog nicht stehen. */
    @Test
    fun abbrechenKoppeltNichtUndSchliesstDenDialog() {
        zeigeListeMitFremdemGeraet()

        composeRule.onNodeWithTag(KARTE).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("dialog_pair_dismiss").performClick()
        composeRule.waitForIdle()

        assertNull(app.container.settingsManager.meterDeviceAddress)
        assertNull(app.container.settingsManager.meterDeviceName)
        assertEquals(
            0,
            composeRule.onAllNodesWithTag("dialog_pair_confirm").fetchSemanticsNodes().size,
        )
    }

    private fun zeigeListeMitFremdemGeraet() {
        composeRule.setContent { MeterScreen(onBack = {}) }
        composeRule.onNodeWithTag(SCAN_BUTTON_TAG).performScrollTo().performClick()
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithTag(KARTE).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        /** Ein Bluetooth-Lautsprecher, kein Schallpegelmesser - genau der Fall vom Geraetetest. */
        val FREMDES_GERAET = BleDevice("20:3C:AE:11:22:33", "Bose Revolve", -55)
        val KARTE = "card_ble_device_${FREMDES_GERAET.address}"
        const val TIMEOUT_MS = 5_000L
    }
}
