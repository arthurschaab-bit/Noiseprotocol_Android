package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Echtes Geraete-Pendant zu [OnboardingScreenComposeTest] (Robolectric, app/src/test) - Teil der
 * Bestandsaufnahme nach dem Datumsbereich-Dialog-Bug (Owner-Auftrag 15.09.2026).
 */
@RunWith(AndroidJUnit4::class)
class OnboardingScreenInstrumentedTest {

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

        composeRule.onAllNodesWithText(composeRule.activity.getString(com.example.lrmprotokoll.R.string.onboarding_1_title), substring = true).onFirst().assertIsDisplayed()
        composeRule.onNodeWithText(nextStr).performClick()
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText(composeRule.activity.getString(com.example.lrmprotokoll.R.string.onboarding_2_title), substring = true).onFirst().assertIsDisplayed()
        composeRule.onNodeWithText(nextStr).performClick()
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText(composeRule.activity.getString(com.example.lrmprotokoll.R.string.onboarding_3_title), substring = true).onFirst().assertIsDisplayed()
        composeRule.onNodeWithText(nextStr).performClick()
        composeRule.waitForIdle()

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
