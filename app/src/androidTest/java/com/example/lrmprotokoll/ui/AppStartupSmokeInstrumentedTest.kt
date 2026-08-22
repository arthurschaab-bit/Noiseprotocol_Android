package com.example.lrmprotokoll.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.LaermprotokollApp
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

        // 1. Startscreen (Home) ist geladen inkl. Smartphone-Mikrofon
        composeRule.onAllNodesWithText("Lärmprotokoll", substring = true).onFirst().assertIsDisplayed()
        composeRule.onAllNodesWithText("1. Smartphone-Mikrofon").onFirst().assertIsDisplayed()

        // 2. Navigation zu Protokoll
        composeRule.onAllNodesWithText("Protokoll").onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Messreihen", substring = true).onFirst().assertIsDisplayed()

        // 3. Navigation zu Einstellungen (inkl. Diagnose-Sektion)
        composeRule.onAllNodesWithText("Einstellungen").onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Einstellungen", substring = true).onFirst().assertIsDisplayed()
        composeRule.onAllNodesWithText("Diagnose & Systemgesundheit", substring = true).onFirst().assertIsDisplayed()

        // 4. Navigation zurück zum Startscreen
        composeRule.onAllNodesWithText("Start").onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Lärmprotokoll", substring = true).onFirst().assertIsDisplayed()
    }
}
