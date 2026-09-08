package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.lrmprotokoll.audio.AudioRecordingService
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MicrophoneStatusBadgeTest {

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
    }
}
