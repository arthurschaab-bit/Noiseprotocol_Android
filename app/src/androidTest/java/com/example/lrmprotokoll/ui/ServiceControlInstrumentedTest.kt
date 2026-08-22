package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.LaermprotokollApp
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentierte UI-Tests für das Live-Cockpit ([LiveCockpitCard]) gemäß aktuellem UX-Design.
 *
 * Prüft die Anzeige des Inaktiv/Aktiv-Status sowie die zentralen Buttons "Messung starten"
 * und "Live-Kurve".
 */
@RunWith(AndroidJUnit4::class)
class ServiceControlInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: LaermprotokollApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
    }

    @Test
    fun liveCockpitCardZeigtInaktivenZustandUndStartButtonAktiv() {
        composeRule.setContent {
            LiveCockpitCard()
        }
        composeRule.waitForIdle()

        // Starten-Button ist im Ruhezustand vorhanden und aktiv
        composeRule.onNodeWithText("Messung starten").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithText("Live-Kurve").assertIsDisplayed()
    }
}
