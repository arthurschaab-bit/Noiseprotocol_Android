package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Echtes Geraete-Pendant zu [SettingsHilfeSectionTest] (Robolectric, app/src/test) - Teil der
 * Bestandsaufnahme nach dem Datumsbereich-Dialog-Bug (Owner-Auftrag 15.09.2026).
 * PROMPT_M9_UX.md Aufgabe 8: das Erststart-Onboarding muss jederzeit aus den Einstellungen wieder
 * aufrufbar sein.
 */
@RunWith(AndroidJUnit4::class)
class SettingsHilfeSectionInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun ohneCallbackFehltDieHilfeKarteKomplett() {
        composeRule.setContent { SettingsScreen(onBack = {}) }
        composeRule.waitForIdle()

        val titel = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_help_title)
        val treffer = composeRule.onAllNodesWithText(titel).fetchSemanticsNodes(atLeastOneRootRequired = false)
        assertEquals(0, treffer.size)
    }

    @Test
    fun knopfInDerHilfeKarteRuftDenOnboardingCallbackAuf() {
        var aufgerufen = false

        composeRule.setContent {
            SettingsScreen(onBack = {}, onShowOnboarding = { aufgerufen = true })
        }
        composeRule.waitForIdle()

        val titel = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_help_title)
        composeRule.onNodeWithText(titel).performScrollTo().performClick()

        val knopfText = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_help_show_onboarding)
        composeRule.onNodeWithText(knopfText).performScrollTo().assertIsDisplayed().performClick()

        assertTrue(aufgerufen)
    }
}
