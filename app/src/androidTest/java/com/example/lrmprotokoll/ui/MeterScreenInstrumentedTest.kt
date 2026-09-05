package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lrmprotokoll.AppContainer
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.meter.BoundDevice
import com.example.lrmprotokoll.meter.ConnectionState
import com.example.lrmprotokoll.meter.FakeMeterTransport
import com.example.lrmprotokoll.meter.Weighting
import com.example.lrmprotokoll.meter.ble.BleDevice
import com.example.lrmprotokoll.meter.ble.BleScannerTestOverrides
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MeterScreenInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: LaermprotokollApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.grantRuntimePermission(composeRule.activity.packageName, android.Manifest.permission.BLUETOOTH_SCAN)
            automation.grantRuntimePermission(composeRule.activity.packageName, android.Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    @After
    fun tearDown() {
        BleScannerTestOverrides.reset()
        app.resetContainer()
    }

    @Test
    fun meterScreenZeigtTitelUndScanButtonUndKopfbereich() {
        var backed = false
        composeRule.setContent { MeterScreen(onBack = { backed = true }) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(composeRule.activity.getString(com.example.lrmprotokoll.R.string.nav_meter)).assertIsDisplayed()
        composeRule.onNodeWithTag(SCAN_BUTTON_TAG).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(composeRule.activity.getString(com.example.lrmprotokoll.R.string.action_back))
            .assertIsDisplayed().performClick()
        assertTrue(backed)
    }

    @Test
    fun scanFehlerAusScannerFlowWirdSichtbarUndSchnellesMehrfachTippenCrashtNicht() {
        BleScannerTestOverrides.scanProvider = {
            flow { throw IllegalStateException("BLE-Scan fehlgeschlagen, errorCode=6") }
        }
        composeRule.setContent { MeterScreen(onBack = {}) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SCAN_BUTTON_TAG).performClick()
        repeat(3) { runCatching { composeRule.onNodeWithTag(SCAN_BUTTON_TAG).performClick() } }
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("errorCode=6", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("errorCode=6", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(SCAN_BUTTON_TAG).assertIsDisplayed()
    }

    @Test
    fun gleicherNameAndereMacZeigtWarnungUndAbbrechenBelaesstPinning() {
        val settings = app.container.settingsManager
        settings.meterDeviceAddress = "AA:AA:AA:AA:AA:AA"
        settings.meterDeviceName = "PCE-323 Test"
        BleScannerTestOverrides.scanProvider = { flowOf(BleDevice("BB:BB:BB:BB:BB:BB", "PCE-323 Test", -42)) }
        composeRule.setContent { MeterScreen(onBack = {}) }
        composeRule.onNodeWithTag(SCAN_BUTTON_TAG).performClick()
        composeRule.waitUntil(5_000L) {
            runCatching { composeRule.onNodeWithTag("card_ble_device_BB:BB:BB:BB:BB:BB").fetchSemanticsNode() }.isSuccess
        }
        composeRule.onNodeWithTag("card_ble_device_BB:BB:BB:BB:BB:BB").performClick()
        composeRule.onNodeWithTag("dialog_spoofing_dismiss").assertIsDisplayed().performClick()
        assertEquals("AA:AA:AA:AA:AA:AA", settings.meterDeviceAddress)
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("dialog_spoofing_dismiss").fetchSemanticsNodes().isEmpty()
        }
        assertTrue(composeRule.onAllNodesWithTag("dialog_spoofing_dismiss").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun gleicherNameAndereMacKannExplizitBestaetigtWerden() {
        val settings = app.container.settingsManager
        settings.meterDeviceAddress = "AA:AA:AA:AA:AA:AA"
        settings.meterDeviceName = "PCE-323 Test"
        BleScannerTestOverrides.scanProvider = { flowOf(BleDevice("CC:CC:CC:CC:CC:CC", "PCE-323 Test", -41)) }
        composeRule.setContent { MeterScreen(onBack = {}) }
        composeRule.onNodeWithTag(SCAN_BUTTON_TAG).performClick()
        composeRule.waitUntil(5_000L) {
            runCatching { composeRule.onNodeWithTag("card_ble_device_CC:CC:CC:CC:CC:CC").fetchSemanticsNode() }.isSuccess
        }
        composeRule.onNodeWithTag("card_ble_device_CC:CC:CC:CC:CC:CC").performClick()
        composeRule.onNodeWithTag("dialog_spoofing_confirm").assertIsDisplayed().performClick()
        composeRule.waitUntil(5_000L) { settings.meterDeviceAddress == "CC:CC:CC:CC:CC:CC" }
        assertEquals("CC:CC:CC:CC:CC:CC", settings.meterDeviceAddress)
    }

    @Test
    fun langeGeraetelisteIstMitEchterWischgesteBisZumEndeErreichbar() {
        BleScannerTestOverrides.scanProvider = {
            flow {
                repeat(18) { index ->
                    emit(
                        BleDevice(
                            address = "AA:BB:CC:DD:EE:${index.toString().padStart(2, '0')}",
                            name = "Testgerät ${index.toString().padStart(2, '0')}",
                            rssi = -30 - index,
                        )
                    )
                }
            }
        }

        composeRule.setContent { MeterScreen(onBack = {}) }
        composeRule.onNodeWithTag(SCAN_BUTTON_TAG).performClick()
        composeRule.waitUntil(5_000L) {
            composeRule.onAllNodesWithTag("card_ble_device_AA:BB:CC:DD:EE:00").fetchSemanticsNodes().isNotEmpty()
        }

        // MeterScreen besitzt hier genau den scrollbaren LazyColumn-Inhalt. Die Geste muss auf
        // diesem Semantics-Knoten landen; ein Swipe auf dem Compose-Root wird nicht zuverlässig
        // an LazyColumn weitergereicht. Wir wischen so lange real nach oben, bis die letzte
        // Gerätekarte tatsächlich sichtbar ist. Zusätzliche Swipes am Listenende wären No-Ops.
        val scrollables = composeRule.onAllNodes(hasScrollAction())
        assertTrue("MeterScreen muss einen scrollbaren Gerätebereich enthalten", scrollables.fetchSemanticsNodes().isNotEmpty())
        val deviceList = scrollables[0]

        var endeSichtbar = false
        for (attempt in 0 until 30) {
            if (runCatching {
                    composeRule.onNodeWithTag(GERAETE_LISTE_ENDE_TAG).assertIsDisplayed()
                }.isSuccess
            ) {
                endeSichtbar = true
                break
            }
            deviceList.performTouchInput { swipeUp() }
            composeRule.waitForIdle()
        }

        assertTrue("Das Ende der langen Geräteliste muss durch echte Wischgesten erreichbar sein", endeSichtbar)
        composeRule.onNodeWithTag(GERAETE_LISTE_ENDE_TAG).assertIsDisplayed()
    }

    @Test
    fun meterScreenSpiegeltLivePegelUndBestaetigteBewertungVonFakeTransport() {
        val fakeTransport = FakeMeterTransport()
        val customContainer = AppContainer(app, meterTransportOverride = fakeTransport)
        app.setCustomContainer(customContainer)
        val device = BoundDevice("AA:BB:CC:DD:EE:FF", "PCE-323 Test")
        customContainer.connectionSupervisor.start(device)
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            customContainer.connectionSupervisor.state.value == ConnectionState.STREAMING
        }
        kotlinx.coroutines.runBlocking {
            fakeTransport.emitFrame(level = 68.5, weighting = Weighting.A, modeAssumptionConfirmed = true)
        }
        try {
            composeRule.setContent { MeterScreen(onBack = {}) }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("68", substring = true).assertIsDisplayed()
            composeRule.onNodeWithText("dB(A)", substring = true).assertIsDisplayed()
            composeRule.onNodeWithText(
                composeRule.activity.getString(com.example.lrmprotokoll.R.string.meter_confirmed_on_device)
            ).assertIsDisplayed()
        } finally {
            customContainer.connectionSupervisor.stop()
        }
    }
}
