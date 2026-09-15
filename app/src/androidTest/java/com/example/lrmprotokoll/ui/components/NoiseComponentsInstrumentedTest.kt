package com.example.lrmprotokoll.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.ui.theme.LaermprotokollTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Echtes Geraete-Pendant zu [NoiseComponentsTest] (Robolectric, app/src/test) - Teil der
 * Bestandsaufnahme nach dem Datumsbereich-Dialog-Bug (Owner-Auftrag 15.09.2026). Reiner,
 * isolierter Komponententest ohne Dialog/Fenster-Kontext.
 */
@RunWith(AndroidJUnit4::class)
class NoiseComponentsInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun noiseCardRendersContentCorrectly() {
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
