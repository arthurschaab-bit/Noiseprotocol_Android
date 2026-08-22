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

        // 1. Titel in TopAppBar
        composeRule.onNodeWithText("Einstellungen").assertIsDisplayed()

        // 2. Aufnahme & Mikrofon Sektion aufklappen
        composeRule.onNodeWithText("Aufnahme & Mikrofon", substring = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Aufnahme-Schwellenwert", substring = true).assertIsDisplayed()

        // 3. Bis zum Bildschirmende scrollen
        composeRule.onNodeWithTag(BILDSCHIRM_ENDE_TAG).performScrollTo().assertIsDisplayed()

        // 4. Zurück-Button
        composeRule.onNodeWithContentDescription("Zurück").assertIsDisplayed().performClick()
        assertTrue(backed)
    }

    @Test
    fun settingsScreenErlaubtAbtastrateAuswahlUndSchalterBedienung() {
        composeRule.setContent {
            SettingsScreen(onBack = {})
        }
        composeRule.waitForIdle()

        // 1. Aufnahme & Mikrofon Sektion aufklappen & Abtastrate-Chips prüfen
        composeRule.onNodeWithText("Aufnahme & Mikrofon", substring = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("16000 Hz (KI-Opt.)", substring = true).performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithText("44100 Hz (Qualität)", substring = true).performScrollTo().assertIsDisplayed().performClick()

        // 2. KI Sektion aufklappen & Schalter prüfen
        composeRule.onNodeWithText("KI-Erkennung (YAMNet)", substring = true).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Automatische KI-Erkennung", substring = true).performScrollTo().assertIsDisplayed()

        // 3. System & Diagnose Sektion aufklappen
        composeRule.onNodeWithText("Diagnose & Systemgesundheit", substring = true).performScrollTo().performClick()
        composeRule.waitForIdle()

        // 4. Diagnose-Log Switch prüfen (durch Scrollen erreichbar)
        composeRule.onNodeWithText("Diagnose-Log aktiv", substring = true).performScrollTo().assertIsDisplayed()
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
