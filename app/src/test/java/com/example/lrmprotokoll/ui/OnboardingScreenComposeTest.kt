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

        val nextStr = composeRule.activity.getString(com.example.lrmprotokoll.R.string.onboarding_next)
        val startStr = composeRule.activity.getString(com.example.lrmprotokoll.R.string.onboarding_finish)

        // 1. Willkommen
        composeRule.onAllNodesWithText(composeRule.activity.getString(com.example.lrmprotokoll.R.string.onboarding_1_title), substring = true).onFirst().assertIsDisplayed()
        composeRule.onNodeWithText(nextStr).performClick()
        composeRule.waitForIdle()

        // 2. Betriebsarten
        composeRule.onAllNodesWithText(composeRule.activity.getString(com.example.lrmprotokoll.R.string.onboarding_2_title), substring = true).onFirst().assertIsDisplayed()
        composeRule.onNodeWithText(nextStr).performClick()
        composeRule.waitForIdle()

        // 3. Berechtigungen
        composeRule.onAllNodesWithText(composeRule.activity.getString(com.example.lrmprotokoll.R.string.onboarding_3_title), substring = true).onFirst().assertIsDisplayed()
        composeRule.onNodeWithText(nextStr).performClick()
        composeRule.waitForIdle()

        // 4. Akku
        composeRule.onAllNodesWithText(composeRule.activity.getString(com.example.lrmprotokoll.R.string.onboarding_4_title), substring = true).onFirst().assertIsDisplayed()
        composeRule.onNodeWithText(startStr).performClick()
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

        val skipStr = composeRule.activity.getString(com.example.lrmprotokoll.R.string.onboarding_skip)
        composeRule.onNodeWithText(skipStr).performClick()
        composeRule.waitForIdle()

        assertTrue(finished)
    }
}
