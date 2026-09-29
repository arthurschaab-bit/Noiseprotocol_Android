package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.audio.AudioRecordingService
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
class LiveCockpitCardTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun liveCockpitCardRendersStartButtonWhenInactive() {
        ApplicationProvider.getApplicationContext<LaermprotokollApp>()

        composeRule.setContent {
            LaermprotokollTheme(darkTheme = true) {
                LiveCockpitCard()
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Start measurement").assertIsDisplayed()
        composeRule.onNodeWithText("Ready to measure").assertIsDisplayed()
    }

    @Test
    fun aufzeichnungshinweisBannerWirdAngezeigtWennHinweisVorhandenUndVerschwindetWieder() {
        ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        AudioRecordingService.testSetzeAufzeichnungsHinweis("Mikrofon ist von einer anderen App belegt – beenden und erneut versuchen.")

        composeRule.setContent {
            LaermprotokollTheme(darkTheme = true) {
                LiveCockpitCard()
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("cockpit_aufzeichnungshinweis_banner").assertIsDisplayed()
        composeRule.onNodeWithTag("cockpit_aufzeichnungshinweis_text").assertIsDisplayed()

        AudioRecordingService.testSetzeAufzeichnungsHinweis(null)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("cockpit_aufzeichnungshinweis_banner").assertDoesNotExist()
    }

    @Test
    fun aufzeichnungshinweisAktionenAusloesenCallbacks() {
        ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        AudioRecordingService.testSetzeAufzeichnungsHinweis("WAV-/Mikrofon-Aufzeichnung unerwartet inaktiv")

        var diagnoseAufgerufen = false
        composeRule.setContent {
            LaermprotokollTheme(darkTheme = true) {
                LiveCockpitCard(
                    onNavigateToDiagnose = { diagnoseAufgerufen = true },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("cockpit_aufzeichnungshinweis_btn_diagnose").performClick()
        org.junit.Assert.assertTrue(diagnoseAufgerufen)

        AudioRecordingService.testSetzeAufzeichnungsHinweis(null)
    }
}
