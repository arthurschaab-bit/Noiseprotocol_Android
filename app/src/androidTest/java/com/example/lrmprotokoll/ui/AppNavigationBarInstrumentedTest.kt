package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.LaermprotokollApp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentierte UI-Tests für die Hauptnavigation und die [AppNavigationBar] gemäß Testplan.
 *
 * Prüft alle 5 Navigationsleisten-Ziele ("Start", "Messgerät", "Protokoll", "Diagnose", "Einstellungen"),
 * schnelles Umschalten, sowie den Regressionsfall, dass die Leiste auf allen Hauptseiten und
 * Unterseiten sichtbar und erreichbar bleibt.
 */
@RunWith(AndroidJUnit4::class)
class AppNavigationBarInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        ApplicationProvider.getApplicationContext<LaermprotokollApp>()
    }

    @Test
    fun alleFuenfNavigationsleistenZieleSindSichtbarUndAntippbar() {
        composeRule.setContent { AppNavigation() }
        composeRule.waitForIdle()

        // 1. Start ist initial aktiv
        composeRule.onNodeWithText("Start").assertIsDisplayed().assertIsSelected()

        // 2. Zu "Messgerät" navigieren
        composeRule.onNodeWithText("Messgerät").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Messgerät").assertIsSelected()

        // 3. Zu "Protokoll" navigieren
        composeRule.onNodeWithText("Protokoll").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Protokoll").assertIsSelected()

        // 4. Zu "Diagnose" navigieren
        composeRule.onNodeWithText("Diagnose").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Diagnose").onFirst().assertIsSelected()

        // 5. Zu "Einstellungen" navigieren
        composeRule.onNodeWithText("Einstellungen").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Einstellungen").assertIsSelected()

        // 6. Zurück zu "Start"
        composeRule.onNodeWithText("Start").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Start").assertIsSelected()
    }

    @Test
    fun mehrfachesSchnellesAntippenDesselbenZielsErzeugtKeineFehler() {
        composeRule.setContent { AppNavigation() }
        composeRule.waitForIdle()

        // Mehrfach hintereinander schnell "Messgerät" und "Start" antippen
        repeat(3) {
            composeRule.onNodeWithText("Messgerät").performClick()
            composeRule.onNodeWithText("Start").performClick()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Start").assertIsDisplayed().assertIsSelected()
    }

    @Test
    fun navigationsleisteBleibtAufJederSeiteSichtbar() {
        composeRule.setContent { AppNavigation() }
        composeRule.waitForIdle()

        val labels = listOf("Start", "Messgerät", "Protokoll", "Diagnose", "Einstellungen")

        labels.forEach { ziel ->
            composeRule.onAllNodesWithText(ziel).onFirst().performClick()
            composeRule.waitForIdle()

            // Auf jedem der 5 Screens müssen alle 5 Nav-Labels sichtbar bleiben
            labels.forEach { label ->
                composeRule.onAllNodesWithText(label).onFirst().assertIsDisplayed()
            }
        }
    }

    @Test
    fun bottomNavHilfsfunktionErkenntPrefixZieleKorrekt() {
        assertTrue(istBottomNavZielAktiv("main", "main"))
        assertTrue(istBottomNavZielAktiv("meter", "meter"))
        assertTrue(istBottomNavZielAktiv("protokoll", "protokoll"))
        assertTrue(istBottomNavZielAktiv("protokoll/{sessionId}", "protokoll"))
        assertTrue(istBottomNavZielAktiv("diagnose", "diagnose"))
        assertTrue(istBottomNavZielAktiv("settings", "settings"))

        assertFalse(istBottomNavZielAktiv("player?path={path}", "main"))
        assertFalse(istBottomNavZielAktiv(null, "main"))
        assertFalse(istBottomNavZielAktiv("protokollverlauf", "protokoll"))
    }
}
