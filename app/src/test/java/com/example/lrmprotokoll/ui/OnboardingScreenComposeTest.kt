package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingScreenComposeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun onboardingKlicktDurchAlleSeitenBisAbschluss() {
        var finished = false

        composeRule.setContent {
            OnboardingScreen(onFinish = { finished = true })
        }
        composeRule.waitForIdle()

        // 1. Willkommen
        composeRule.onAllNodesWithText("Willkommen beim Lärmprotokoll", substring = true).onFirst().assertIsDisplayed()
        composeRule.onNodeWithText("Weiter").performClick()
        composeRule.waitForIdle()

        // 2. Betriebsarten
        composeRule.onAllNodesWithText("Zwei Betriebsarten", substring = true).onFirst().assertIsDisplayed()
        composeRule.onNodeWithText("Weiter").performClick()
        composeRule.waitForIdle()

        // 3. Berechtigungen
        composeRule.onAllNodesWithText("Berechtigungen", substring = true).onFirst().assertIsDisplayed()
        composeRule.onNodeWithText("Weiter").performClick()
        composeRule.waitForIdle()

        // 4. Akku
        composeRule.onAllNodesWithText("Akku-Optimierung", substring = true).onFirst().assertIsDisplayed()
        composeRule.onNodeWithText("Starten").performClick()
        composeRule.waitForIdle()

        assertTrue(finished)
    }

    @Test
    fun onboardingUeberspringenFunktioniert() {
        var finished = false

        composeRule.setContent {
            OnboardingScreen(onFinish = { finished = true })
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Überspringen").performClick()
        composeRule.waitForIdle()

        assertTrue(finished)
    }
}
