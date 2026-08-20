package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regressionstest für den in PR #27 (SettingsScreen-Scroll-Bug) behobenen, aber damals nicht
 * mit einem Test abgesicherten Fall: der Screen ist so lang, dass der letzte Abschnitt
 * (Akku-Optimierung) ohne verticalScroll auf der äußeren Column unerreichbar wäre.
 *
 * M7c Aufgabe 4 fordert das Nachholen ausdrücklich ein, jetzt mit demselben produktiven-
 * Screen-Testmuster wie [MeterScreenComposeTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h240dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenComposeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun bildschirmEndeIstPerScrollErreichbar() {
        ApplicationProvider.getApplicationContext<LaermprotokollApp>()

        composeRule.setContent { SettingsScreen(onBack = {}) }

        composeRule.onNodeWithTag(BILDSCHIRM_ENDE_TAG).performScrollTo().assertIsDisplayed()
    }
}
