package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.messreihe.MeterTriggerSource
import com.example.lrmprotokoll.meter.MeterFrame
import com.example.lrmprotokoll.meter.Weighting
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AudioTriggerSettingsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: LaermprotokollApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        app.container.settingsManager.audioTriggerQuelle = "AUTO"
    }

    private fun frame(level: Double) = MeterFrame(
        level = level,
        weighting = Weighting.A,
        timeWeighting = null,
        range = null,
        holdMax = false,
        holdMin = false,
        receivedAt = Instant.now(),
        modeAssumptionConfirmed = true
    )

    @Test
    fun triggerQuelleStandardIstAuto() {
        val settings = app.container.settingsManager
        assertEquals("AUTO", settings.audioTriggerQuelle)

        // 1. AUTO mit Messgerät: Messwert vom PCE-323 wird geprüft
        val mitMeter = MeterTriggerSource.auswerten(
            letzterMeterFrame = frame(72.0),
            mikrofonDb = 35.0,
            activeSchwelle = 65f,
            triggerQuelle = settings.audioTriggerQuelle
        )
        assertTrue("Mit PCE-323 über Schwelle muss AUTO auslösen", mitMeter.ausgeloest)
        assertTrue(mitMeter.meterConnected)
        assertEquals(72.0, mitMeter.pegel, 0.01)

        // 2. AUTO ohne Messgerät: Mikrofonwert wird geprüft
        val ohneMeter = MeterTriggerSource.auswerten(
            letzterMeterFrame = null,
            mikrofonDb = 68.0,
            activeSchwelle = 65f,
            triggerQuelle = settings.audioTriggerQuelle
        )
        assertTrue("Ohne Messgerät muss AUTO auf Mikrofon zurückgreifen und auslösen", ohneMeter.ausgeloest)
        assertFalse(ohneMeter.meterConnected)
        assertEquals(68.0, ohneMeter.pegel, 0.01)
    }

    @Test
    fun triggerQuellePce323UndMikrofonVerhalten() {
        val settings = app.container.settingsManager

        // Modus "PCE_323": Mikrofon allein löst nicht aus
        settings.audioTriggerQuelle = "PCE_323"
        val pceModusOhneMeter = MeterTriggerSource.auswerten(
            letzterMeterFrame = null,
            mikrofonDb = 90.0,
            activeSchwelle = 65f,
            triggerQuelle = settings.audioTriggerQuelle
        )
        assertFalse(pceModusOhneMeter.ausgeloest)

        // Modus "MIKROFON": PCE-323 wird ignoriert
        settings.audioTriggerQuelle = "MIKROFON"
        val micModus = MeterTriggerSource.auswerten(
            letzterMeterFrame = frame(95.0),
            mikrofonDb = 40.0,
            activeSchwelle = 65f,
            triggerQuelle = settings.audioTriggerQuelle
        )
        assertFalse(micModus.ausgeloest)
    }

    @Test
    fun settingsScreenErlaubtUmschaltungDerTriggerQuelle() {
        val settings = app.container.settingsManager
        settings.audioTriggerQuelle = "AUTO"

        composeRule.setContent {
            SettingsScreen(onBack = {})
        }
        composeRule.waitForIdle()

        // Sektion öffnen
        val secTitle = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_section_thresholds)
        composeRule.onNodeWithText(secTitle, substring = true).performClick()
        composeRule.waitForIdle()

        val autoStr = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_trigger_source_auto)
        val meterStr = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_trigger_source_meter)
        val micStr = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_trigger_source_mic)

        // Chips prüfen
        composeRule.onNodeWithText(autoStr).assertIsDisplayed()
        composeRule.onNodeWithText(meterStr).assertIsDisplayed()
        composeRule.onNodeWithText(micStr).assertIsDisplayed()

        // Auf "Nur PCE-323" umschalten
        composeRule.onNodeWithText(meterStr).performClick()
        composeRule.waitForIdle()
        assertEquals("PCE_323", settings.audioTriggerQuelle)

        // Auf "Nur Mikrofon" umschalten
        composeRule.onNodeWithText(micStr).performClick()
        composeRule.waitForIdle()
        assertEquals("MIKROFON", settings.audioTriggerQuelle)

        // Zurück auf "Automatisch (Standard)"
        composeRule.onNodeWithText(autoStr).performClick()
        composeRule.waitForIdle()
        assertEquals("AUTO", settings.audioTriggerQuelle)
    }
}
