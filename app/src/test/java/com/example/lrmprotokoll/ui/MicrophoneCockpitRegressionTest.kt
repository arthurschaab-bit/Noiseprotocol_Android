package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.AppContainer
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.audio.AudioRecordingService
import com.example.lrmprotokoll.meter.FakeMeterTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MicrophoneCockpitRegressionTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var app: LaermprotokollApp

    @Suppress("UNCHECKED_CAST")
    private fun <T> serviceFlow(name: String): MutableStateFlow<T> =
        AudioRecordingService::class.java.getDeclaredField(name).let {
            it.isAccessible = true
            it.get(null) as MutableStateFlow<T>
        }

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        app.setCustomContainer(AppContainer(app, FakeMeterTransport()))
        serviceFlow<Boolean>("_laeuft").value = true
        serviceFlow<Double?>("_currentMicDb").value = null
    }

    @After fun cleanup() {
        serviceFlow<Boolean>("_laeuft").value = false
        serviceFlow<Double?>("_currentMicDb").value = null
        app.resetContainer()
    }

    private fun showCockpit() {
        composeRule.setContent {
            LiveCockpitCard(modifier = Modifier.verticalScroll(rememberScrollState()))
        }
    }

    @Test fun keinErfundenerPegelUndMikrofonAenderungenWerdenAngezeigt() {
        showCockpit()
        composeRule.onNodeWithText("36.3").assertDoesNotExist()
        composeRule.onNodeWithText("--.-").assertExists()
        composeRule.runOnIdle { serviceFlow<Double?>("_currentMicDb").value = 48.2 }
        composeRule.onNodeWithText("48.2").assertExists()
        composeRule.runOnIdle { serviceFlow<Double?>("_currentMicDb").value = 61.7 }
        composeRule.onNodeWithText("61.7").assertExists()
        composeRule.onNodeWithText("48.2").assertDoesNotExist()
        composeRule.runOnIdle { serviceFlow<Double?>("_currentMicDb").value = null }
        composeRule.onNodeWithText("--.-").assertExists()
    }

    @Test fun manuellesLabelZeigtUndSpeichertMikrofonwertBeimSpeichern() {
        serviceFlow<Double?>("_currentMicDb").value = 48.2
        showCockpit()
        composeRule.onNodeWithTag(MARK_NOISE_EVENT_BUTTON_TAG).performScrollTo().performClick()
        composeRule.onNodeWithText("48.2 dB (Mikrofon, unkalibriert)", substring = true).assertExists()
        composeRule.runOnIdle { serviceFlow<Double?>("_currentMicDb").value = 61.7 }
        composeRule.onNodeWithText("61.7 dB (Mikrofon, unkalibriert)", substring = true).assertExists()
        val before = System.currentTimeMillis()
        composeRule.onNodeWithTag(SAVE_NOISE_EVENT_BUTTON_TAG).performClick()
        composeRule.waitUntil(10_000) {
            runBlocking { app.container.database.noiseDao().getAll().first().isNotEmpty() }
        }
        val record = runBlocking { app.container.database.noiseDao().getAll().first().first() }
        assertEquals(61.7, record.dbValue, 0.001)
        assertNull(record.calibratedDbA)
        assertFalse(record.meterConnected)
        assertTrue(record.timestamp >= before)
        assertNotNull(record.label)
    }
}
