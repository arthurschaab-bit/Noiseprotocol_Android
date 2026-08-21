package com.example.lrmprotokoll.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * 2. Die MainActivity mit NavHost, Theme und Drawer-Layout sauber gerendert wird.
 * 3. Alle Hauptscreens (Start, Messgerät, Protokoll, Diagnose, Einstellungen) fehlerfrei geladen werden können.
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

        // 1. Startscreen (Home) ist geladen
        composeRule.onNodeWithText("Lärmprotokoll", substring = true).assertIsDisplayed()

        // 2. Navigation zum Messgerät über Menü-Drawer
        composeRule.onNodeWithContentDescription("Menü").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Messgerät").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("PCE-323", substring = true).assertIsDisplayed()

        // 3. Navigation zu Protokoll
        composeRule.onNodeWithContentDescription("Menü").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Protokoll").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Messreihen", substring = true).assertIsDisplayed()

        // 4. Navigation zu Diagnose
        composeRule.onNodeWithContentDescription("Menü").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Diagnose").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Diagnose & Systemstatus", substring = true).assertIsDisplayed()

        // 5. Navigation zu Einstellungen
        composeRule.onNodeWithContentDescription("Menü").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Einstellungen").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Aufnahme & Mikrofon", substring = true).assertIsDisplayed()
    }
}
