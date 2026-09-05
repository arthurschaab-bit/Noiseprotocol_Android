package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.LaermprotokollApp
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class AudioPlayerScreenInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        cacheDir = app.cacheDir
    }

    private fun erstelleGueltigeWavDatei(dateiname: String): File {
        val datei = File(cacheDir, dateiname)
        val sampleRate = 16000
        val numSamples = 8000
        val dataSize = numSamples * 2
        val totalSize = 36 + dataSize

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(totalSize)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(sampleRate)
            putInt(sampleRate * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray())
            putInt(dataSize)
        }.array()

        FileOutputStream(datei).use { fos ->
            fos.write(header)
            fos.write(ByteArray(dataSize))
        }
        return datei
    }

    @Test
    fun audioPlayerLaedtGueltigeWavDateiUndReleasedPlayerBeimVerlassen() {
        val testDatei = erstelleGueltigeWavDatei("test_aufnahme.wav")
        var showPlayer by mutableStateOf(true)
        var released = false

        composeRule.setContent {
            if (showPlayer) {
                AudioPlayerScreen(
                    filePath = testDatei.absolutePath,
                    onBack = { showPlayer = false },
                    onPlayerReleasedForTest = { released = true },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(composeRule.activity.getString(com.example.lrmprotokoll.R.string.player_title)).assertIsDisplayed()
        composeRule.onNodeWithText("test_aufnahme.wav").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(composeRule.activity.getString(com.example.lrmprotokoll.R.string.audio_play))
            .assertIsDisplayed().performClick()

        composeRule.onNodeWithContentDescription(composeRule.activity.getString(com.example.lrmprotokoll.R.string.action_back))
            .assertIsDisplayed().performClick()

        composeRule.waitUntil(timeoutMillis = 5_000L) { released }
        assertTrue("MediaPlayer.release() muss beim Verlassen des Screens ausgeführt werden", released)
        testDatei.delete()
    }

    @Test
    fun audioPlayerZeigtFehlerBeiNichtExistierenderDateiOhneAbsturz() {
        var backed = false
        composeRule.setContent {
            AudioPlayerScreen(filePath = "/ungueltiger/pfad/nicht_vorhanden.wav", onBack = { backed = true })
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Datei kann nicht abgespielt werden", substring = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(composeRule.activity.getString(com.example.lrmprotokoll.R.string.action_back))
            .assertIsDisplayed().performClick()
        assertTrue(backed)
    }

    @Test
    fun audioPlayerZeigtFehlerBeiBeschaedigterDateiOhneAbsturz() {
        val korrupteDatei = File(cacheDir, "korrupt.wav").apply { writeBytes(byteArrayOf(0, 1, 2, 3)) }
        composeRule.setContent { AudioPlayerScreen(filePath = korrupteDatei.absolutePath, onBack = {}) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Datei kann nicht abgespielt werden", substring = true).assertIsDisplayed()
        korrupteDatei.delete()
    }

    @Test
    fun audioPlayerZeigtFehlerBeiGeloeschterDateiUndDeaktiviertPlayButton() {
        val datei = erstelleGueltigeWavDatei("temporaer_geloescht.wav")
        val pfad = datei.absolutePath
        assertTrue(datei.delete())

        composeRule.setContent { AudioPlayerScreen(filePath = pfad, onBack = {}) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Datei kann nicht abgespielt werden", substring = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(composeRule.activity.getString(com.example.lrmprotokoll.R.string.audio_play))
            .assertIsNotEnabled()
    }

    @Test
    fun audioPlayerZeigtHinweisBeiBlankPfadFuerReinePegelmessung() {
        composeRule.setContent { AudioPlayerScreen(filePath = "", onBack = {}) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Reine Pegelmessung", substring = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(composeRule.activity.getString(com.example.lrmprotokoll.R.string.audio_play))
            .assertIsNotEnabled()
    }
}
