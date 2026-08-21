package com.example.lrmprotokoll.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.activity.ComponentActivity
import com.example.lrmprotokoll.LaermprotokollApp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivityLaunchTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun mainActivityStartetErfolgreich() {
        val container = ApplicationProvider.getApplicationContext<LaermprotokollApp>().container
        container.settingsManager.onboardingCompleted = true

        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Lärmprotokoll", substring = true).onFirst().assertIsDisplayed()
    }
}
