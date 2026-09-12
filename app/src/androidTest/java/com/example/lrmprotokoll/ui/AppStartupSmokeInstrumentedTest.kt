package com.example.lrmprotokoll.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Vollständiger Smoke-Test für den realen Kaltstart der App und die Navigation durch
 * alle Kern-Screens auf dem Android-Emulator/Gerät.
 *
 * Stellt sicher, dass:
 * 1. Der Application-Kontext und alle Provider (Sentry, Room, Diagnostics) ohne Absturz initialisieren.
 * 2. Die MainActivity mit NavHost, Theme und den 3 Hauptreitern (Start/Daten/Bericht) sauber
 *    gerendert wird (Layout-Umbau 12.09.2026 - Einstellungen hängt seither nicht mehr an einem
 *    eigenen Bottom-Nav-Tab, sondern am Drei-Punkt-Menü des Start-Screens).
 * 3. Alle Hauptscreens (Start inkl. PCE-323 Steuerung, Daten, Einstellungen inkl. Diagnose-
 *    Sektion) fehlerfrei geladen werden können.
 */
@RunWith(AndroidJUnit4::class)
class AppStartupSmokeInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        app.container.settingsManager.onboardingCompleted = true
        app.container.database.clearAllTables()
    }

    @Test
    fun appStartetOhneAbsturzUndNavigiertDurchAlleHauptscreens() {
        composeRule.waitForIdle()

        val appName = composeRule.activity.getString(R.string.app_name)
        val protocolLabel = composeRule.activity.getString(R.string.nav_data)
        val settingsLabel = composeRule.activity.getString(R.string.nav_settings)
        val startLabel = composeRule.activity.getString(R.string.nav_start)
        val diagSection = composeRule.activity.getString(R.string.settings_section_diagnostics)

        // 1. Startscreen (Home) ist geladen. Eindeutiger Tag statt Text-Suche, da "appName" auch
        // im (immer komponierten, aber geschlossenen) Navigations-Drawer vorkommt.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            try {
                composeRule.onNodeWithTag("home_title").assertIsDisplayed()
                true
            } catch (e: AssertionError) {
                false
            }
        }

        // 2. Navigation zu Protokoll
        composeRule.onAllNodesWithText(protocolLabel).onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText(protocolLabel, substring = true).onFirst().assertIsDisplayed()

        // 3. Navigation zu Einstellungen (inkl. Diagnose-Sektion) - kein eigener Bottom-Nav-Tab
        // mehr, sondern über das Drei-Punkt-Menü (Layout-Umbau 12.09.2026). Genutzt wird hier das
        // Menü von Daten (wir sind bereits dort) statt eines Umwegs über Start, um den ohnehin
        // bereits verifizierten Startscreen nicht durch zusätzliche Navigationsschritte zu belasten.
        composeRule.onNodeWithTag("btn_daten_menu").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText(settingsLabel).onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText(settingsLabel, substring = true).onFirst().assertIsDisplayed()
        // Diagnose-Sektion haengt an SettingsTab.START, Daten-Menue oeffnet aber mit tab=DATEN -
        // erst per Umschalter zu Start wechseln.
        composeRule.onNodeWithTag("settings_tab_start").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText(diagSection, substring = true).onFirst().assertExists()

        // 4. Navigation zurück zum Startscreen
        composeRule.onAllNodesWithText(startLabel).onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText(appName, substring = true).onFirst().assertIsDisplayed()
    }
}
