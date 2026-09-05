package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.AppContainer
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.meter.BoundDevice
import com.example.lrmprotokoll.meter.FakeMeterTransport
import com.example.lrmprotokoll.meter.MeasurementRange
import com.example.lrmprotokoll.meter.TimeWeighting
import com.example.lrmprotokoll.meter.Weighting
import com.example.lrmprotokoll.ui.theme.LaermprotokollTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Systematische instrumentierte UI- und E2E-Tests für [MeterScreen].
 *
 * Verifiziert auf dem Android-Emulator (API 34 ATD):
 * 1. TopAppBar-Navigation (Drawer-Öffnen vs. Zurück-Pfeil).
 * 2. Scan-Button-Interaktion und fehlerfreies Abfangen des Scan-Flows.
 * 3. Gekoppelter vs. ungekoppelter Zustand (Verbinden-Button & Adressanzeige).
 * 4. Live-Pegelanzeige und Parameterkarte bei aktivem Streaming (via FakeMeterTransport).
 * 5. Warnhinweis bei unbestätigter Frequenzbewertung.
 */
@RunWith(AndroidJUnit4::class)
class MeterScreenAndroidTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: LaermprotokollApp
    private lateinit var fakeTransport: FakeMeterTransport
    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        fakeTransport = FakeMeterTransport()
        container = AppContainer(app, fakeTransport)
        app.setCustomContainer(container)
        container.settingsManager.meterDeviceAddress = null
        container.settingsManager.meterDeviceName = null
    }

    @After
    fun tearDown() {
        container.connectionSupervisor.stop()
        container.settingsManager.meterDeviceAddress = null
        container.settingsManager.meterDeviceName = null
        app.resetContainer()
    }

    @Test
    fun topAppBarNavigiertZurueckOderOeffnetDrawer() {
        var drawerOpened = false
        var backClicked = false

        // 1. Mit Drawer-Callback
        composeRule.setContent {
            LaermprotokollTheme {
                MeterScreen(
                    onBack = { backClicked = true },
                    onOpenDrawer = { drawerOpened = true }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("btn_navigation_drawer").assertIsDisplayed().performClick()
        assertTrue("Drawer-Callback sollte aufgerufen worden sein", drawerOpened)

        // 2. Ohne Drawer-Callback -> Zurück-Pfeil
        composeRule.setContent {
            LaermprotokollTheme {
                MeterScreen(
                    onBack = { backClicked = true },
                    onOpenDrawer = null
                )
            }
        }
        composeRule.waitForIdle()

        val backDescription = composeRule.activity.getString(R.string.action_back)
        composeRule.onNodeWithContentDescription(backDescription).assertIsDisplayed().performClick()
        assertTrue("Back-Callback sollte aufgerufen worden sein", backClicked)
    }

    @Test
    fun scanButtonStartetScanUndFaengtFehlerOhneCrash() {
        composeRule.setContent {
            LaermprotokollTheme {
                MeterScreen(onBack = {})
            }
        }
        composeRule.waitForIdle()

        // Scan-Button anscrollen und klicken
        val scanButton = composeRule.onNodeWithTag(SCAN_BUTTON_TAG).performScrollTo()
        scanButton.assertIsDisplayed()
        scanButton.performClick()
        composeRule.waitForIdle()

        // Der Button existiert weiterhin und die App ist nicht abgestürzt
        composeRule.onNodeWithTag(SCAN_BUTTON_TAG).assertIsDisplayed()
    }

    @Test
    fun ungekoppeltesGeraetZeigtKeinenVerbindenButton() {
        container.settingsManager.meterDeviceAddress = null
        container.settingsManager.meterDeviceName = null

        composeRule.setContent {
            LaermprotokollTheme {
                MeterScreen(onBack = {})
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("btn_meter_connect").assertDoesNotExist()
    }

    @Test
    fun gekoppeltesGeraetZeigtAdresseUndVerbindenButton() {
        val testAddress = "12:34:56:78:9A:BC"
        val testName = "PCE-323-PROTOTYP"
        container.settingsManager.meterDeviceAddress = testAddress
        container.settingsManager.meterDeviceName = testName

        composeRule.setContent {
            LaermprotokollTheme {
                MeterScreen(onBack = {})
            }
        }
        composeRule.waitForIdle()

        // Gekoppeltes Gerät wird mit Name und Adresse aufgeführt
        composeRule.onNodeWithText(testName, substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(testAddress, substring = true).assertIsDisplayed()

        // Verbinden-Button ist sichtbar
        composeRule.onNodeWithTag("btn_meter_connect").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun liveStreamingZeigtPegelUndParameterkarte() {
        val testAddress = "12:34:56:78:9A:BC"
        val testName = "PCE-323-LIVE"
        container.settingsManager.meterDeviceAddress = testAddress
        container.settingsManager.meterDeviceName = testName

        runBlocking {
            fakeTransport.connect(BoundDevice(testAddress, testName))
            fakeTransport.emitFrame(
                level = 68.4,
                weighting = Weighting.A,
                timeWeighting = TimeWeighting.FAST,
                range = MeasurementRange.RANGE_30_130,
                modeAssumptionConfirmed = true
            )
            container.connectionSupervisor.start(BoundDevice(testAddress, testName))
        }

        composeRule.setContent {
            LaermprotokollTheme {
                MeterScreen(onBack = {})
            }
        }
        composeRule.waitForIdle()

        // Live-Pegelanzeige prüfen
        composeRule.onNodeWithTag("live_meter_level_display")
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains("68.4")

        // Parameter-Karte prüfen
        composeRule.onNodeWithTag("card_meter_parameters")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun unbestaetigteFrequenzbewertungZeigtWarnhinweis() {
        val testAddress = "12:34:56:78:9A:BC"
        val testName = "PCE-323-UNCONFIRMED"
        container.settingsManager.meterDeviceAddress = testAddress
        container.settingsManager.meterDeviceName = testName

        runBlocking {
            fakeTransport.connect(BoundDevice(testAddress, testName))
            fakeTransport.emitFrame(
                level = 55.2,
                weighting = Weighting.A,
                timeWeighting = TimeWeighting.FAST,
                range = MeasurementRange.RANGE_30_130,
                modeAssumptionConfirmed = false
            )
            container.connectionSupervisor.start(BoundDevice(testAddress, testName))
        }

        composeRule.setContent {
            LaermprotokollTheme {
                MeterScreen(onBack = {})
            }
        }
        composeRule.waitForIdle()

        // Live-Pegel enthält unbestätigten Wert ohne Klammer (z.B. "55.2 dB")
        composeRule.onNodeWithTag("live_meter_level_display")
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains("55.2 dB")

        // Warnhinweis wird angezeigt
        val warningText = composeRule.activity.getString(R.string.meter_unconfirmed_warning)
        composeRule.onNodeWithText(warningText).performScrollTo().assertIsDisplayed()
    }
}
