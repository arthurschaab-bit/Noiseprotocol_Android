package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.AppContainer
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.audio.AudioRecordingService
import com.example.lrmprotokoll.meter.FakeMeterTransport
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StopConfirmationRegressionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: LaermprotokollApp

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        app.setCustomContainer(AppContainer(app, FakeMeterTransport()))
        AudioRecordingService.testSetzeLaeuft(true)
    }

    @After
    fun cleanup() {
        AudioRecordingService.testSetzeLaeuft(false)
        AudioRecordingService.testSetzeAudioAufnahmeAktiv(false)
        app.resetContainer()
    }

    @Test
    fun messungBeendenVerlangtExpliziteBestaetigung() {
        composeRule.setContent {
            LiveCockpitCard(modifier = Modifier.verticalScroll(rememberScrollState()))
        }

        composeRule.onNodeWithTag(END_MEASUREMENT_BUTTON_TAG)
            .performScrollTo()
            .performClick()

        composeRule.onNodeWithTag(END_MEASUREMENT_CONFIRM_DIALOG_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Messung wirklich beenden?").assertIsDisplayed()
        composeRule.onNodeWithText("Abbrechen").performClick()
        composeRule.onNodeWithTag(END_MEASUREMENT_CONFIRM_DIALOG_TAG).assertDoesNotExist()
    }
}
