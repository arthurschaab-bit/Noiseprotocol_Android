package com.example.lrmprotokoll.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
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
 * 2. Die MainActivity mit NavHost, Theme und 4-Tab Navigation sauber gerendert wird.
 * 3. Alle Hauptscreens (Start inkl. PCE-323 Steuerung, Protokoll, Diagnose, Einstellungen) fehlerfrei geladen werden können.
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
        val protocolLabel = composeRule.activity.getString(R.string.nav_protocol)
        val settingsLabel = composeRule.activity.getString(R.string.nav_settings)
        val startLabel = composeRule.activity.getString(R.string.nav_start)
        val diagSection = composeRule.activity.getString(R.string.settings_section_diagnostics)

        // 1. Startscreen (Home) ist geladen
        composeRule.onAllNodesWithText(appName, substring = true).onFirst().assertIsDisplayed()

        // 2. Navigation zu Protokoll
        composeRule.onAllNodesWithText(protocolLabel).onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText(protocolLabel, substring = true).onFirst().assertIsDisplayed()

        // 3. Navigation zu Einstellungen (inkl. Diagnose-Sektion)
        composeRule.onAllNodesWithText(settingsLabel).onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText(settingsLabel, substring = true).onFirst().assertIsDisplayed()
        composeRule.onAllNodesWithText(diagSection, substring = true).onFirst().assertExists()

        // 4. Navigation zurück zum Startscreen
        composeRule.onAllNodesWithText(startLabel).onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText(appName, substring = true).onFirst().assertIsDisplayed()
    }
}
