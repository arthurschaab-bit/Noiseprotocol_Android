package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regressionstest für M7c Aufgabe 3 (Bestandsaufnahme-Befund 4, Verbesserungsvorschlag 3):
 * Diagnose war zuvor nur über ein Info-Icon ohne Text erreichbar - die neue `NavigationBar`
 * beschriftet jedes Ziel. Läuft gegen die echte NoiseProtocolApp()-Funktion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeNavigationComposeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun diagnoseIstUeberEinenBeschrifteteNavigationseintragErreichbar() {
        ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        var diagnoseAufgerufen = false

        composeRule.setContent {
            NoiseProtocolApp(
                onNavigateToPlayer = {}, onNavigateToSettings = {}, onNavigateToMeter = {},
                onNavigateToProtokoll = {}, onNavigateToDiagnose = { diagnoseAufgerufen = true },
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Diagnose").assertIsDisplayed()
        composeRule.onNodeWithText("Diagnose").performClick()

        assert(diagnoseAufgerufen) { "onNavigateToDiagnose wurde nicht ausgelöst" }
    }

    @Test
    fun alleFuenfNavigationszieleSindBeschriftet() {
        ApplicationProvider.getApplicationContext<LaermprotokollApp>()

        composeRule.setContent {
            NoiseProtocolApp(
                onNavigateToPlayer = {}, onNavigateToSettings = {}, onNavigateToMeter = {},
                onNavigateToProtokoll = {}, onNavigateToDiagnose = {},
            )
        }
        composeRule.waitForIdle()

        listOf("Start", "Messgerät", "Protokoll", "Diagnose", "Einstellungen").forEach { label ->
            composeRule.onNodeWithText(label).assertIsDisplayed()
        }
    }
}
