package com.example.lrmprotokoll.ui

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.audio.AudioRecordingService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentierte UI-Tests für das Live-Cockpit ([LiveCockpitCard]) gemäß aktuellem UX-Design.
 *
 * Prüft die Anzeige des Inaktiv/Bereit-Status sowie des zentralen Buttons "Messung starten".
 */
@RunWith(AndroidJUnit4::class)
class ServiceControlInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: LaermprotokollApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        stoppeServiceUndWarteAufIdle()
    }

    @After
    fun tearDown() {
        stoppeServiceUndWarteAufIdle()
    }

    private fun stoppeServiceUndWarteAufIdle() {
        app.stopService(Intent(app, AudioRecordingService::class.java))
        runBlocking {
            withTimeout(5_000L) {
                AudioRecordingService.laeuft.first { !it }
            }
        }
        assertFalse(
            "Der Idle-Test darf nur gegen einen tatsächlich gestoppten AudioRecordingService laufen",
            AudioRecordingService.laeuft.value,
        )
    }

    @Test
    fun liveCockpitCardZeigtInaktivenZustandUndStartButtonAktiv() {
        composeRule.setContent {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                LiveCockpitCard()
            }
        }
        val readyText = composeRule.activity.getString(com.example.lrmprotokoll.R.string.cockpit_ready_to_measure)

        composeRule.onNodeWithText(readyText).assertIsDisplayed()

        // In der realen Startseite liegt die Karte in einem scrollbaren Screen. Der Standalone-Test
        // bildet das nach und prüft, dass der zentrale Start-Button tatsächlich erreichbar ist.
        composeRule.onNodeWithTag(START_MEASUREMENT_BUTTON_TAG)
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
    }
}
