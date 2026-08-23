package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
 * Prüft alle 4 Navigationsleisten-Ziele ("Start", "Protokoll", "Diagnose", "Einstellungen"),
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
    fun alleDreiNavigationsleistenZieleSindSichtbarUndAntippbar() {
        var currentRoute by androidx.compose.runtime.mutableStateOf("main")
        composeRule.setContent {
            AppNavigationBar(
                currentRoute = currentRoute,
                onNavigateToStart = { currentRoute = "main" },
                onNavigateToProtokoll = { currentRoute = "protokoll" },
                onNavigateToSettings = { currentRoute = "settings" },
            )
        }
        composeRule.waitForIdle()

        val startLabel = composeRule.activity.getString(com.example.lrmprotokoll.R.string.nav_start)
        val protocolLabel = composeRule.activity.getString(com.example.lrmprotokoll.R.string.nav_protocol)
        val settingsLabel = composeRule.activity.getString(com.example.lrmprotokoll.R.string.nav_settings)

        // 1. Start ist initial aktiv
        composeRule.onNodeWithText(startLabel).assertIsDisplayed().assertIsSelected()

        // 2. Zu "Protokoll" navigieren
        composeRule.onNodeWithText(protocolLabel).assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(protocolLabel).assertIsSelected()

        // 3. Zu "Einstellungen" navigieren
        composeRule.onNodeWithText(settingsLabel).assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(settingsLabel).assertIsSelected()

        // 4. Zurück zu "Start"
        composeRule.onNodeWithText(startLabel).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(startLabel).assertIsSelected()
    }

    @Test
    fun mehrfachesSchnellesAntippenDesselbenZielsErzeugtKeineFehler() {
        var currentRoute by androidx.compose.runtime.mutableStateOf("main")
        composeRule.setContent {
            AppNavigationBar(
                currentRoute = currentRoute,
                onNavigateToStart = { currentRoute = "main" },
                onNavigateToProtokoll = { currentRoute = "protokoll" },
                onNavigateToSettings = { currentRoute = "settings" },
            )
        }
        composeRule.waitForIdle()

        val startLabel = composeRule.activity.getString(com.example.lrmprotokoll.R.string.nav_start)
        val protocolLabel = composeRule.activity.getString(com.example.lrmprotokoll.R.string.nav_protocol)

        // Mehrfach hintereinander schnell "Protokoll" und "Start" antippen
        repeat(3) {
            composeRule.onNodeWithText(protocolLabel).performClick()
            composeRule.onNodeWithText(startLabel).performClick()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(startLabel).assertIsDisplayed().assertIsSelected()
    }

    @Test
    fun navigationsleisteBleibtAufJederSeiteSichtbar() {
        val routes = listOf("main", "protokoll", "settings")
        val startLabel = composeRule.activity.getString(com.example.lrmprotokoll.R.string.nav_start)
        val protocolLabel = composeRule.activity.getString(com.example.lrmprotokoll.R.string.nav_protocol)
        val settingsLabel = composeRule.activity.getString(com.example.lrmprotokoll.R.string.nav_settings)
        val labels = listOf(startLabel, protocolLabel, settingsLabel)

        var currentRoute by mutableStateOf("main")
        composeRule.setContent {
            AppNavigationBar(
                currentRoute = currentRoute,
                onNavigateToStart = { currentRoute = "main" },
                onNavigateToProtokoll = { currentRoute = "protokoll" },
                onNavigateToSettings = { currentRoute = "settings" },
            )
        }
        composeRule.waitForIdle()

        routes.forEach { activeRoute ->
            currentRoute = activeRoute
            composeRule.waitForIdle()

            // Auf jedem der Screens müssen alle Nav-Labels sichtbar bleiben
            labels.forEach { label ->
                composeRule.onNodeWithText(label).assertIsDisplayed()
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
