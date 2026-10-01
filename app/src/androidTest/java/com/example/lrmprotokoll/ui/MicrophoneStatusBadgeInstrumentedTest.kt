package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.R
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
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.mic_status_wav_inactive)).assertIsDisplayed()

        composeRule.runOnIdle { AudioRecordingService.testSetzeAudioAufnahmeAktiv(true) }
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.mic_status_wav_active)).assertIsDisplayed()
    }

    @Test
    fun aktivesWavVerlangtBestaetigungVorStop() {
        AudioRecordingService.testSetzeAudioAufnahmeAktiv(true)
        composeRule.setContent {
            MicrophoneStatusBadge(audioMonitoringActive = true, recordWavAudio = true)
        }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.mic_status_wav_active)).performClick()
        composeRule.onNodeWithTag(WAV_STOP_CONFIRM_DIALOG_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.mic_stop_confirm_title)).assertIsDisplayed()

        // Regressionsklasse Datumsbereich-Dialog: beide Buttons muessen auf dem echten
        // Bildschirm sichtbar sein und Abbrechen muss den Dialog wirklich schliessen.
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.mic_stop_confirm_action)).assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.mic_stop_confirm_cancel)).assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(WAV_STOP_CONFIRM_DIALOG_TAG).assertDoesNotExist()
    }
}
