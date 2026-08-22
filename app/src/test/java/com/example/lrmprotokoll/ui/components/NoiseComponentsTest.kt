package com.example.lrmprotokoll.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.ui.theme.LaermprotokollTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoiseComponentsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun noiseCardRendersContentCorrectly() {
        ApplicationProvider.getApplicationContext<LaermprotokollApp>()

        composeRule.setContent {
            LaermprotokollTheme(darkTheme = true) {
                NoiseHeaderCard(
                    title = "Messung Aktiv",
                    subtitle = "PCE-323 Verbunden",
                    statusBadge = { StatusPill(text = "Kalibriert", type = StatusPillType.CALIBRATED) }
                ) {
                    Text("Live-Pegel: 52.4 dB")
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Messung Aktiv").assertIsDisplayed()
        composeRule.onNodeWithText("PCE-323 Verbunden").assertIsDisplayed()
        composeRule.onNodeWithText("Kalibriert").assertIsDisplayed()
        composeRule.onNodeWithText("Live-Pegel: 52.4 dB").assertIsDisplayed()
    }
}
