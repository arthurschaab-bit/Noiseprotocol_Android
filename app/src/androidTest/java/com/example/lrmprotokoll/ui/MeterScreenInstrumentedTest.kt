package com.example.lrmprotokoll.ui

import android.content.Intent
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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lrmprotokoll.AppContainer
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.audio.AudioRecordingService
import com.example.lrmprotokoll.meter.BoundDevice
import com.example.lrmprotokoll.meter.ConnectionState
import com.example.lrmprotokoll.meter.FakeMeterTransport
import com.example.lrmprotokoll.meter.Weighting
import com.example.lrmprotokoll.meter.ble.BleDevice
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    private var customContainerInstalled = false
    private var vorherigeMeterAdresse: String? = null
    private var vorherigerMeterName: String? = null

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        val settings = app.container.settingsManager
        vorherigeMeterAdresse = settings.meterDeviceAddress
        vorherigerMeterName = settings.meterDeviceName
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.grantRuntimePermission(composeRule.activity.packageName, android.Manifest.permission.BLUETOOTH_SCAN)
            automation.grantRuntimePermission(composeRule.activity.packageName, android.Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    @After
    fun tearDown() {
        // Nur einen tatsächlich gestarteten Foreground Service stoppen. Ein stopService() direkt
        // nach startForegroundService(), aber bevor onStartCommand/startForeground gelaufen ist,
        // erzeugt auf Android 14 selbst einen ForegroundServiceDidNotStartInTimeException.
        if (AudioRecordingService.laeuft.value) {
            app.stopService(Intent(app, AudioRecordingService::class.java))
            runBlocking {
                withTimeout(5_000L) {
                    AudioRecordingService.laeuft.first { !it }
                }
            }
        }

        val settings = app.container.settingsManager
        settings.meterDeviceAddress = vorherigeMeterAdresse
        settings.meterDeviceName = vorherigerMeterName

        if (customContainerInstalled) {
            app.resetContainer()
            customContainerInstalled = false
        }
    }

    private fun installContainer(container: AppContainer): AppContainer {
        app.setCustomContainer(container)
        customContainerInstalled = true
        return container
    }

    private fun installScanProvider(provider: () -> Flow<BleDevice>) {
        installContainer(AppContainer(app, bleScanProviderOverride = provider))
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
        installScanProvider {
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
        installScanProvider { flowOf(BleDevice("BB:BB:BB:BB:BB:BB", "PCE-323 Test", -42)) }
        val settings = app.container.settingsManager
        settings.meterDeviceAddress = "AA:AA:AA:AA:AA:AA"
        settings.meterDeviceName = "PCE-323 Test"
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
        installScanProvider { flowOf(BleDevice("CC:CC:CC:CC:CC:CC", "PCE-323 Test", -41)) }
        val settings = app.container.settingsManager
        settings.meterDeviceAddress = "AA:AA:AA:AA:AA:AA"
        settings.meterDeviceName = "PCE-323 Test"
        composeRule.setContent { MeterScreen(onBack = {}) }
        composeRule.onNodeWithTag(SCAN_BUTTON_TAG).performClick()
        composeRule.waitUntil(5_000L) {
            runCatching { composeRule.onNodeWithTag("card_ble_device_CC:CC:CC:CC:CC:CC").fetchSemanticsNode() }.isSuccess
        }
        composeRule.onNodeWithTag("card_ble_device_CC:CC:CC:CC:CC:CC").performClick()
        composeRule.onNodeWithTag("dialog_spoofing_confirm").assertIsDisplayed().performClick()
        composeRule.waitUntil(5_000L) { settings.meterDeviceAddress == "CC:CC:CC:CC:CC:CC" }
        assertEquals("CC:CC:CC:CC:CC:CC", settings.meterDeviceAddress)

        // pinne() startet in Produktion absichtlich den Foreground Service. Vor dem Cleanup muss
        // dieser Start vollständig durch onStartCommand/startForeground gelaufen sein; sonst kann
        // ein zu früher Test-Cleanup selbst den Android-14-FGS-Timeout auslösen.
        composeRule.waitUntil(timeoutMillis = 5_000L) { AudioRecordingService.laeuft.value }
        assertTrue("Das bestätigte Pinning muss den Verbindungsdienst starten", AudioRecordingService.laeuft.value)
    }

    @Test
    fun langeGeraetelisteReagiertAufEchteWischgesteUndEndeIstErreichbar() {
        val fakeScanVollstaendig = AtomicBoolean(false)
        installScanProvider {
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
                fakeScanVollstaendig.set(true)
            }
        }

        composeRule.setContent { MeterScreen(onBack = {}) }
        composeRule.onNodeWithTag(SCAN_BUTTON_TAG).performClick()
        composeRule.waitUntil(5_000L) { fakeScanVollstaendig.get() }
        composeRule.waitForIdle()
        composeRule.waitUntil(5_000L) {
            composeRule.onAllNodesWithText("Testgerät 00").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Testgerät 00").assertIsDisplayed()

        val scrollables = composeRule.onAllNodes(hasScrollAction())
        assertTrue("MeterScreen muss einen scrollbaren Gerätebereich enthalten", scrollables.fetchSemanticsNodes().isNotEmpty())
        val deviceList = scrollables[0]

        // Emulator-Mehrwert: echte Touch-Koordinaten müssen die LazyColumn tatsächlich bewegen.
        repeat(3) {
            deviceList.performTouchInput { swipeUp() }
            composeRule.waitForIdle()
        }
        val erstesGeraetNichtMehrSichtbar = runCatching {
            composeRule.onNodeWithText("Testgerät 00").assertIsDisplayed()
        }.isFailure
        assertTrue("Eine echte Wischgeste muss die Geräteliste sichtbar bewegen", erstesGeraetNichtMehrSichtbar)

        // LazyColumn komponiert nur sichtbare/nahe Items. Deshalb mit echten Gesten weiterscrollen,
        // bis die explizit markierte letzte Gerätekarte Teil des Semantics-Baums ist. Erst dann
        // sorgt performScrollTo() dafür, dass die Karte vollständig im Viewport liegt.
        repeat(24) {
            if (composeRule.onAllNodesWithTag(GERAETE_LISTE_ENDE_TAG).fetchSemanticsNodes().isNotEmpty()) {
                return@repeat
            }
            deviceList.performTouchInput { swipeUp() }
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithTag(GERAETE_LISTE_ENDE_TAG)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun meterScreenSpiegeltLivePegelUndBestaetigteBewertungVonFakeTransport() {
        val fakeTransport = FakeMeterTransport()
        val customContainer = installContainer(AppContainer(app, meterTransportOverride = fakeTransport))
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
