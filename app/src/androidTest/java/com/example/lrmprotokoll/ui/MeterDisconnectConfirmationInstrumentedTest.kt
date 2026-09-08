package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.AppContainer
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.meter.FakeMeterTransport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MeterDisconnectConfirmationInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: LaermprotokollApp

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        app.setCustomContainer(AppContainer(app, meterTransportOverride = FakeMeterTransport()))
        app.container.settingsManager.meterDeviceAddress = "AA:BB:CC:DD:EE:FF"
        app.container.settingsManager.meterDeviceName = "PCE-323"
    }

    @After
    fun cleanup() {
        app.container.settingsManager.meterDeviceAddress = null
        app.container.settingsManager.meterDeviceName = null
        app.resetContainer()
    }

    @Test
    fun entkoppelnTrenntNichtOhneBestaetigung() {
        composeRule.setContent { MeterScreen(onBack = {}) }
        composeRule.onNodeWithTag("btn_meter_unpair").performScrollTo().performClick()

        composeRule.onNodeWithTag(METER_DISCONNECT_CONFIRM_DIALOG_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Bluetooth-Verbindung beenden?").assertIsDisplayed()
        assertEquals("AA:BB:CC:DD:EE:FF", app.container.settingsManager.meterDeviceAddress)

        composeRule.onNodeWithText("Abbrechen").performClick()
        assertEquals("AA:BB:CC:DD:EE:FF", app.container.settingsManager.meterDeviceAddress)
    }
}
