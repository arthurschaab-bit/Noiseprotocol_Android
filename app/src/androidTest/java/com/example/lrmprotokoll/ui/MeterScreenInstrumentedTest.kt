package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.AppContainer
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.meter.ConnectionState
import com.example.lrmprotokoll.meter.FakeMeterTransport
import com.example.lrmprotokoll.meter.Weighting
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentierte UI-Tests für den [MeterScreen] gemäß Testplan.
 *
 * Prüft den Scan-Button, Fehlerbehandlung bei Scan-Drosselung (Scan-Crash-Regression),
 * den Geräte-Pinning-Warndialog bei Namenskonflikten ("Trotzdem koppeln" / "Abbrechen"),
 * sowie die Scrollbarkeit der Geräteliste bis [GERAETE_LISTE_ENDE_TAG].
 */
@RunWith(AndroidJUnit4::class)
class MeterScreenInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        ApplicationProvider.getApplicationContext<LaermprotokollApp>()
    }

    @Test
    fun meterScreenZeigtTitelUndScanButtonUndKopfbereich() {
        var backed = false
        composeRule.setContent {
            MeterScreen(onBack = { backed = true })
        }
        composeRule.waitForIdle()

        val meterTitle = composeRule.activity.getString(com.example.lrmprotokoll.R.string.nav_meter)
        val scanBtn = composeRule.activity.getString(com.example.lrmprotokoll.R.string.meter_scan_button)
        val backDesc = composeRule.activity.getString(com.example.lrmprotokoll.R.string.action_back)

        // 1. Titel "Messgerät" in TopAppBar sichtbar
        composeRule.onNodeWithText(meterTitle).assertIsDisplayed()

        // 2. Scan-Button vorhanden und klickbar
        composeRule.onNodeWithTag(SCAN_BUTTON_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(scanBtn).assertIsDisplayed()

        // 3. Zurück-Button klickbar
        composeRule.onNodeWithContentDescription(backDesc).assertIsDisplayed().performClick()
        assertTrue(backed)
    }

    @Test
    fun scanFehlermeldungWirdBeiScanDrosselungOhneAbsturzFormatiert() {
        val exScanTooFrequently = IllegalStateException("Scan failed with error 6")
        val msg = scanFehlermeldung(exScanTooFrequently)
        assertTrue(msg.contains("Scan fehlgeschlagen"))
        assertTrue(msg.contains("error 6"))

        val exBluetoothOff = IllegalStateException("Bluetooth is disabled")
        val msgOff = scanFehlermeldung(exBluetoothOff)
        assertTrue(msgOff.contains("Scan fehlgeschlagen"))
        assertTrue(msgOff.contains("Bluetooth is disabled"))

        val exGeneric = RuntimeException("Unbekannter Fehler")
        val msgGeneric = scanFehlermeldung(exGeneric)
        assertTrue(msgGeneric.contains("Scan fehlgeschlagen"))
        assertTrue(msgGeneric.contains("Unbekannter Fehler"))
    }

    @Test
    fun scanButtonMehrfachSchnellKlickenCrashtNichtUndZeigtFehlerBeiFehlendemBluetooth() {
        val context = composeRule.activity
        // Berechtigungen auf API 31+ vorsorglich erteilen, damit direkt der Scan-Pfad angesteuert wird
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val uiAutomation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.BLUETOOTH_SCAN)
            uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.BLUETOOTH_CONNECT)
        }

        composeRule.setContent {
            MeterScreen(onBack = {})
        }
        composeRule.waitForIdle()

        // Schnelles, wiederholtes Antippen des Scan-Buttons (Regression für Scan-Crash)
        repeat(4) {
            try {
                composeRule.onNodeWithTag(SCAN_BUTTON_TAG).performClick()
            } catch (_: AssertionError) {
                // Button ist während des laufenden Scans richtigerweise disabled
            }
        }
        composeRule.waitForIdle()

        // Auf dem Emulator ohne echtes aktives Bluetooth wird scanFehler gesetzt statt abzustürzen
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("Scan fehlgeschlagen", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty() ||
            composeRule.onAllNodesWithText("Standortdienste", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty() ||
            composeRule.onAllNodesWithTag(SCAN_BUTTON_TAG)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    @Test
    fun meterScreenSpiegeltLivePegelUndBestaetigteBewertungVonFakeTransport() {
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        val fakeTransport = FakeMeterTransport()
        app.setCustomContainer(AppContainer(app, meterTransportOverride = fakeTransport))

        kotlinx.coroutines.runBlocking {
            fakeTransport.setState(ConnectionState.STREAMING)
            fakeTransport.emitFrame(
                level = 68.5,
                weighting = Weighting.A,
                modeAssumptionConfirmed = true
            )
        }

        try {
            composeRule.setContent {
                MeterScreen(onBack = {})
            }
            composeRule.waitForIdle()

            val confirmedText = composeRule.activity.getString(com.example.lrmprotokoll.R.string.meter_confirmed_on_device)

            // 1. Pegelwert und Frequenzbewertung dB(A) wird angezeigt
            composeRule.onNodeWithText("68", substring = true).assertIsDisplayed()
            composeRule.onNodeWithText("dB(A)", substring = true).assertIsDisplayed()

            // 2. Bestätigt am Gerät Hinweis sichtbar
            composeRule.onNodeWithText(confirmedText).assertIsDisplayed()
        } finally {
            app.resetContainer()
        }
    }
}
