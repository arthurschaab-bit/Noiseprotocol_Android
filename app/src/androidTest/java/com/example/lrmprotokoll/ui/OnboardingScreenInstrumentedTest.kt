package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
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

    /**
     * Checkliste Button/Screen-Coverage (Phase 1b): OnboardingScreen wird inline in MainActivity
     * gerendert (keine eigene Activity/Route, kein configChanges-Override im Manifest) - eine
     * Bildschirmdrehung waehrend des Onboardings fuehrt zu einer echten Neukomposition.
     * `currentPage` nutzte urspruenglich `remember` statt `rememberSaveable` und wurde dabei auf
     * Seite 1 zurueckgesetzt (gefunden bei dieser Coverage-Pruefung, gefixt im selben PR).
     * `StateRestorationTester` simuliert genau diesen Zyklus, ohne eine echte Geraetedrehung zu
     * brauchen.
     */
    @Test
    fun fortschrittBleibtNachKonfigurationsAenderungErhalten() {
        val restorationTester = StateRestorationTester(composeRule)

        restorationTester.setContent {
            OnboardingScreen(onFinish = {})
        }
        composeRule.waitForIdle()

        val nextStr = composeRule.activity.getString(com.example.lrmprotokoll.R.string.onboarding_next)
        composeRule.onNodeWithText(nextStr).performClick()
        composeRule.waitForIdle()

        val zweiteSeite = composeRule.activity.getString(com.example.lrmprotokoll.R.string.onboarding_2_title)
        composeRule.onAllNodesWithText(zweiteSeite, substring = true).onFirst().assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText(zweiteSeite, substring = true).onFirst().assertIsDisplayed()
    }
}
