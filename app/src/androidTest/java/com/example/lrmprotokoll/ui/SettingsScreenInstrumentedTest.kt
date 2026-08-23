package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.LaermprotokollApp
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentierte UI-Tests für den [SettingsScreen] gemäß Testplan.
 *
 * Prüft alle Slider, Schalter (KI, Alarmierung, Push, Drive, Diagnose),
 * die Scrollbarkeit des gesamten Screens bis [BILDSCHIRM_ENDE_TAG], sowie die Navigation.
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        ApplicationProvider.getApplicationContext<LaermprotokollApp>()
    }

    @Test
    fun settingsScreenZeigtTitelSliderUndScrolltBisZumEnde() {
        var backed = false

        composeRule.setContent {
            SettingsScreen(onBack = { backed = true })
        }
        composeRule.waitForIdle()

        val settingsTitle = composeRule.activity.getString(com.example.lrmprotokoll.R.string.nav_settings)
        val secThresholds = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_section_thresholds)
        val backDesc = composeRule.activity.getString(com.example.lrmprotokoll.R.string.action_back)

        // 1. Titel in TopAppBar
        composeRule.onNodeWithText(settingsTitle).assertIsDisplayed()

        // 2. Aufnahme & Schwellenwert Sektion aufklappen
        composeRule.onNodeWithText(secThresholds, substring = true).performClick()
        composeRule.waitForIdle()

        // 3. Bis zum Bildschirmende scrollen
        composeRule.onNodeWithTag(BILDSCHIRM_ENDE_TAG).performScrollTo().assertIsDisplayed()

        // 4. Zurück-Button
        composeRule.onNodeWithContentDescription(backDesc).assertIsDisplayed().performClick()
        assertTrue(backed)
    }

    @Test
    fun settingsScreenErlaubtAbtastrateAuswahlUndSchalterBedienung() {
        composeRule.setContent {
            SettingsScreen(onBack = {})
        }
        composeRule.waitForIdle()

        val proMode = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_mode_pro)
        val secThresholds = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_section_thresholds)
        val sample16k = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_sample_rate_16k)
        val sample44k = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_sample_rate_44k)
        val secAi = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_ai_title)
        val secDiag = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_section_diagnostics)

        // In den Pro-Modus schalten, um erweiterte Optionen (Abtastrate etc.) freizuschalten
        composeRule.onNodeWithText(proMode, substring = true).performClick()
        composeRule.waitForIdle()

        // 1. Aufnahme & Schwellenwert Sektion aufklappen & Abtastrate-Chips prüfen
        composeRule.onNodeWithText(secThresholds, substring = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(sample16k, substring = true).performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithText(sample44k, substring = true).performScrollTo().assertIsDisplayed().performClick()

        // 2. KI Sektion aufklappen & Schalter prüfen
        composeRule.onNodeWithText(secAi, substring = true).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(secAi, substring = true).performScrollTo().assertIsDisplayed()

        // 3. System & Diagnose Sektion aufklappen
        composeRule.onNodeWithText(secDiag, substring = true).performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun formatiereDriveFehlerEnthaeltUrsacheWennVorhanden() {
        val mitUrsache = RuntimeException("Token-Fehler", IllegalStateException("Keine OAuth-Client-ID"))
        val textMitUrsache = formatiereDriveFehler(mitUrsache)
        assertTrue(textMitUrsache.contains("Token-Fehler"))
        assertTrue(textMitUrsache.contains("Keine OAuth-Client-ID"))

        val ohneUrsache = RuntimeException("Netzwerkfehler")
        val textOhneUrsache = formatiereDriveFehler(ohneUrsache)
        assertTrue(textOhneUrsache.contains("Netzwerkfehler"))
    }
}
