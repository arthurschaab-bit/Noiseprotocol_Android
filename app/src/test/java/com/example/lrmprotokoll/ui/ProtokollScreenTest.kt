package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.audio.AudioRecordingService
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Bugfix (Owner-Feedback 12.09.2026): "+ Neue Messung" im Protokollreiter fuehrte waehrend einer
 * bereits laufenden Messung nur zurueck ins Cockpit - eine zweite, parallele Messung gibt es
 * nicht. Der Button darf deshalb nicht angezeigt werden, solange [AudioRecordingService.laeuft]
 * aktiv ist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProtokollScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @After
    fun aufraeumen() {
        AudioRecordingService.testSetzeLaeuft(false)
    }

    @Test
    fun neueMessungButtonIstSichtbarOhneLaufendeMessung() {
        ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        AudioRecordingService.testSetzeLaeuft(false)

        composeRule.setContent {
            ProtokollScreen(
                onBack = {},
                onOpenSession = {},
                onStartNewMeasurement = {},
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("fab_new_measurement").assertIsDisplayed()
    }

    @Test
    fun neueMessungButtonIstBeiLaufenderMessungAusgeblendet() {
        ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        AudioRecordingService.testSetzeLaeuft(true)

        composeRule.setContent {
            ProtokollScreen(
                onBack = {},
                onOpenSession = {},
                onStartNewMeasurement = {},
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("fab_new_measurement").assertDoesNotExist()
    }
}
