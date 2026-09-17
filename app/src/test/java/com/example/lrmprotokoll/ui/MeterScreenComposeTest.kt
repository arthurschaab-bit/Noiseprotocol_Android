package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.AppContainer
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.meter.FakeMeterTransport
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regressionstest zu M7c Aufgabe 4 (Bestandsaufnahme-Befund 4.2): MeterScreen stand vor der
 * Umstellung auf ein einziges LazyColumn in einer Column ohne verticalScroll, mit einem
 * verschachtelten LazyColumn nur fuer die Geraeteliste - derselbe Bug-Klasse wie der
 * SettingsScreen-Scroll-Bug (PR #27), hier aber vorbeugend behoben statt nach einem Feldbericht.
 *
 * Anders als der generische Spike (PR #29) laeuft dieser Test gegen die ECHTE, produktive
 * MeterScreen()-Funktion mit dem vollen AppContainer (ueber LaermprotokollApp, wie im
 * Manifest als android:name deklariert) - klaert die dort offen gelassene Frage, ob das unter
 * Robolectric genauso sauber funktioniert wie das triviale Beispiel. Ergebnis: ja, siehe unten.
 *
 * Feste, kleine Bildschirmgroesse (w320dp-h240dp) erzwingt, dass der feste Kopfbereich (Status,
 * Bonding-Warnung, Trenner, "Neues Gerät koppeln", Scan-Button) allein schon mehr Platz braucht
 * als der Viewport hoch ist - ohne echte Bluetooth-Geraete im Scan-Ergebnis noetig zu haben.
 */
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h240dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeterScreenComposeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: LaermprotokollApp

    /**
     * CI-Fund (Issue #160): ohne einen [FakeMeterTransport] verwendet [MeterScreen] den echten,
     * produktiven Container - `container.meterTransport` loest dann auf einen echten
     * `BleMeterTransport` auf, dessen `ConnectionSupervisor`-Hintergrundaktivitaet unter
     * Robolectrics `GraphicsMode.NATIVE` (echter Choreographer, keine Fake-Uhr) Composes
     * Idle-Erkennung nie zur Ruhe kommen laesst - beobachtet als sporadische
     * `AppNotIdleException`, ausschliesslich in den beiden Testklassen, die `MeterScreen()` ohne
     * Fake-Transport rendern. Gleiches Muster wie in jedem anderen Meter-bezogenen Test dieses
     * Repos (z.B. MicrophoneCockpitRegressionTest).
     */
    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.setCustomContainer(AppContainer(app, FakeMeterTransport()))
    }

    @After
    fun tearDown() {
        app.resetContainer()
    }

    @Test
    fun scanButtonAmEndeDesKopfbereichsIstPerScrollErreichbar() {
        composeRule.setContent { MeterScreen(onBack = {}) }

        composeRule.onNodeWithTag(SCAN_BUTTON_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun entkoppelnButtonWirdAngezeigtUndLoeschtGeraeteadresse() {
        app.container.settingsManager.meterDeviceAddress = "AA:BB:CC:DD:EE:FF"

        composeRule.setContent { MeterScreen(onBack = {}) }

        composeRule.onNodeWithTag("btn_meter_unpair").performScrollTo().assertIsDisplayed().performClick()
        // Entkoppeln verlangt seit der Sicherheitsabfrage eine explizite Bestätigung.
        composeRule.onNodeWithTag(METER_DISCONNECT_CONFIRM_DIALOG_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("PCE trennen").performClick()

        assertNull(app.container.settingsManager.meterDeviceAddress)
    }
}
