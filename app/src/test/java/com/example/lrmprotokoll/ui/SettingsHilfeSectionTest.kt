package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * PROMPT_M9_UX.md Aufgabe 8: das Erststart-Onboarding muss "jederzeit aus den Einstellungen
 * wieder aufrufbar" sein, nicht nur einmalig beim allerersten Start. Diese Karte ist der
 * dafuer vorgesehene Weg.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h480dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsHilfeSectionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun ohneCallbackFehltDieHilfeKarteKomplett() {
        // Gegenprobe: ohne onShowOnboarding darf kein Knopf existieren, der ins Leere klickt.
        ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        composeRule.setContent { SettingsScreen(onBack = {}) }

        val titel = composeRule.activity.getString(R.string.settings_help_title)
        val treffer = composeRule.onAllNodesWithText(titel).fetchSemanticsNodes(atLeastOneRootRequired = false)
        assertEquals(0, treffer.size)
    }

    @Test
    fun knopfInDerHilfeKarteRuftDenOnboardingCallbackAuf() {
        ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        var aufgerufen = false

        composeRule.setContent {
            SettingsScreen(onBack = {}, onShowOnboarding = { aufgerufen = true })
        }

        val titel = composeRule.activity.getString(R.string.settings_help_title)
        composeRule.onNodeWithText(titel).performScrollTo().performClick()

        val knopfText = composeRule.activity.getString(R.string.settings_help_show_onboarding)
        composeRule.onNodeWithText(knopfText).performScrollTo().assertIsDisplayed().performClick()

        assertTrue(aufgerufen)
    }
}
