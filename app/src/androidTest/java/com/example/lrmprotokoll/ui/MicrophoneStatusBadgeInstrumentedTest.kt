package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.audio.AudioRecordingService
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Echtes Geraete-Pendant zu [MicrophoneStatusBadgeTest] (Robolectric, app/src/test) - Teil der
 * Bestandsaufnahme nach dem Datumsbereich-Dialog-Bug (Owner-Auftrag 15.09.2026).
 */
@RunWith(AndroidJUnit4::class)
class MicrophoneStatusBadgeInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @After
    fun cleanup() {
        AudioRecordingService.testSetzeAudioAufnahmeAktiv(false)
    }

    @Test
    fun verwendetEchtenRuntimeStatusStattServiceFlag() {
        AudioRecordingService.testSetzeAudioAufnahmeAktiv(false)
        composeRule.setContent {
            // Der uebergebene Alt-Parameter ist absichtlich true: die Anzeige muss trotzdem den
            // echten AudioRecord-Zustand verwenden.
            MicrophoneStatusBadge(audioMonitoringActive = true, recordWavAudio = true)
        }
        composeRule.onNodeWithText("WAV: INAKTIV").assertIsDisplayed()

        composeRule.runOnIdle { AudioRecordingService.testSetzeAudioAufnahmeAktiv(true) }
        composeRule.onNodeWithText("WAV: AKTIV").assertIsDisplayed()
    }

    @Test
    fun aktivesWavVerlangtBestaetigungVorStop() {
        AudioRecordingService.testSetzeAudioAufnahmeAktiv(true)
        composeRule.setContent {
            MicrophoneStatusBadge(audioMonitoringActive = true, recordWavAudio = true)
        }

        composeRule.onNodeWithText("WAV: AKTIV").performClick()
        composeRule.onNodeWithTag(WAV_STOP_CONFIRM_DIALOG_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("WAV-Aufzeichnung beenden?").assertIsDisplayed()

        // Regressionsklasse Datumsbereich-Dialog: beide Buttons muessen auf dem echten
        // Bildschirm sichtbar sein und Abbrechen muss den Dialog wirklich schliessen.
        composeRule.onNodeWithText("WAV beenden").assertIsDisplayed()
        composeRule.onNodeWithText("Abbrechen").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(WAV_STOP_CONFIRM_DIALOG_TAG).assertDoesNotExist()
    }
}
