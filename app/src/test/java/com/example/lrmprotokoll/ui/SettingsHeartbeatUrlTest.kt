package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.data.SettingsManager
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regressionstest fuer Befund J (`docs/BEFUNDE_BUNDLES_2026-10-07.md`): Ein UI-Umbau hat am
 * 21.08.2026 das Eingabefeld der Totmannschaltungs-URL entfernt, die Funktion war danach
 * unerreichbar. Dieser Test faellt rot aus, sobald das Feld wieder verschwindet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsHeartbeatUrlTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var settings: SettingsManager

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        settings = app.container.settingsManager
        settings.isProMode = true
        settings.alarmierungAktiv = true
        settings.heartbeatUrl = ""
    }

    private fun alarmKarteOeffnen() {
        composeRule.setContent { SettingsScreen(onBack = {}) }
        composeRule.waitForIdle()
        val titel = composeRule.activity.getString(R.string.settings_alerting_title)
        composeRule.onNodeWithText(titel, substring = true).performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    private fun feld() = composeRule.onNodeWithTag("input_heartbeat_url").performScrollTo()

    @Test
    fun alarmkarteZeigtHeartbeatFeldUndProbePingKnopf() {
        alarmKarteOeffnen()

        feld().assertIsDisplayed()
        composeRule.onNodeWithTag("btn_heartbeat_probe").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun gueltigeHttpsUrlWirdGespeichert() {
        alarmKarteOeffnen()

        feld().performTextInput("https://hc-ping.com/abc-123")
        composeRule.waitForIdle()

        assertEquals("https://hc-ping.com/abc-123", settings.heartbeatUrl)
    }

    @Test
    fun ungueltigeEingabeWirdNichtGespeichertUndZeigtHinweis() {
        alarmKarteOeffnen()
        val hinweis = composeRule.activity.getString(R.string.settings_heartbeat_url_invalid)

        feld().performTextInput("http://hc-ping.com/abc-123")
        composeRule.waitForIdle()
        assertEquals("http:// ist keine gueltige Totmann-URL", "", settings.heartbeatUrl)
        composeRule.onNodeWithText(hinweis).assertIsDisplayed()

        feld().performTextClearance()
        feld().performTextInput("kein-url")
        composeRule.waitForIdle()
        assertEquals("", settings.heartbeatUrl)
        composeRule.onNodeWithText(hinweis).assertIsDisplayed()
    }

    @Test
    fun leerenDesFeldesSpeichertLeerenString() {
        settings.heartbeatUrl = "https://hc-ping.com/abc-123"
        alarmKarteOeffnen()

        feld().performTextClearance()
        composeRule.waitForIdle()

        assertEquals("", settings.heartbeatUrl)
    }
}
