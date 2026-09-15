package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.AppContainer
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.audio.AudioRecordingService
import com.example.lrmprotokoll.meter.FakeMeterTransport
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Echtes Geraete-Pendant zu [StopConfirmationRegressionTest] (Robolectric, app/src/test) - Teil
 * der Bestandsaufnahme nach dem Datumsbereich-Dialog-Bug (Owner-Auftrag 15.09.2026).
 */
@RunWith(AndroidJUnit4::class)
class StopConfirmationRegressionInstrumentedTest {

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

        // Regressionsklasse Datumsbereich-Dialog: der Dialog und beide Buttons muessen auf dem
        // echten Bildschirm wirklich sichtbar sein.
        composeRule.onNodeWithTag(END_MEASUREMENT_CONFIRM_DIALOG_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Messung wirklich beenden?").assertIsDisplayed()
        composeRule.onNodeWithText("Abbrechen").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag(END_MEASUREMENT_CONFIRM_DIALOG_TAG).assertDoesNotExist()
    }
}
