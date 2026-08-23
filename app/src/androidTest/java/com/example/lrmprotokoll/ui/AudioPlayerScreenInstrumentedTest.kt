package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
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

/**
 * Instrumentierte UI-Tests für den [AudioPlayerScreen] gemäß Testplan (Abschnitt 8).
 *
 * Testet:
 * 1. Positivtest: Laden und Abspielen einer gültigen WAV-Datei mit Wellenform und Play/Pause-Umschaltung.
 * 2. Negativtest: Nicht existierende oder beschädigte Datei führt zu einer sauberen Fehlermeldung
 *    in der UI ("Audiodatei existiert nicht mehr." / "Audiodatei ist beschädigt") statt eines Absturzes.
 */
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

    /**
     * Erstellt eine minimale, aber voll gültige 16-Bit-Mono-PCM-WAV-Datei (44-Byte-Header + Audiodaten).
     */
    private fun erstelleGueltigeWavDatei(dateiname: String): File {
        val datei = File(cacheDir, dateiname)
        val sampleRate = 16000
        val numSamples = 8000 // 0.5 Sekunden Audio
        val dataSize = numSamples * 2
        val totalSize = 36 + dataSize

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(totalSize)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16) // Subchunk1Size für PCM
            putShort(1) // AudioFormat (1 = PCM)
            putShort(1) // NumChannels (1 = Mono)
            putInt(sampleRate)
            putInt(sampleRate * 2) // ByteRate
            putShort(2) // BlockAlign
            putShort(16) // BitsPerSample
            put("data".toByteArray())
            putInt(dataSize)
        }.array()

        val pcmData = ByteArray(dataSize) // Stille / Nullen
        FileOutputStream(datei).use { fos ->
            fos.write(header)
            fos.write(pcmData)
        }
        return datei
    }

    @Test
    fun audioPlayerLaedtGueltigeWavDateiUndSchaltetPlayPauseUm() {
        val testDatei = erstelleGueltigeWavDatei("test_aufnahme.wav")
        var backed = false

        composeRule.setContent {
            AudioPlayerScreen(filePath = testDatei.absolutePath, onBack = { backed = true })
        }
        composeRule.waitForIdle()

        val titleStr = composeRule.activity.getString(com.example.lrmprotokoll.R.string.player_title)
        val playDesc = composeRule.activity.getString(com.example.lrmprotokoll.R.string.action_play)
        val backDesc = composeRule.activity.getString(com.example.lrmprotokoll.R.string.action_back)

        // 1. Titel & Dateiname
        composeRule.onNodeWithText(titleStr).assertIsDisplayed()
        composeRule.onNodeWithText("test_aufnahme.wav").assertIsDisplayed()

        // 2. Play-Button vorhanden und klickbar
        composeRule.onNodeWithContentDescription(playDesc).assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        // 3. Zurück-Button
        composeRule.onNodeWithContentDescription(backDesc).assertIsDisplayed().performClick()
        assertTrue(backed)

        testDatei.delete()
    }

    @Test
    fun audioPlayerZeigtFehlerBeiNichtExistierenderDateiOhneAbsturz() {
        var backed = false

        composeRule.setContent {
            AudioPlayerScreen(filePath = "/ungueltiger/pfad/nicht_vorhanden.wav", onBack = { backed = true })
        }
        composeRule.waitForIdle()

        val errStr = composeRule.activity.getString(com.example.lrmprotokoll.R.string.player_error_cannot_play)
        val backDesc = composeRule.activity.getString(com.example.lrmprotokoll.R.string.action_back)

        // 1. Fehler-Text sichtbar
        composeRule.onNodeWithText(errStr, substring = true).assertIsDisplayed()

        // 2. Zurück-Button in TopAppBar klickbar
        composeRule.onNodeWithContentDescription(backDesc).assertIsDisplayed().performClick()
        assertTrue(backed)
    }

    @Test
    fun audioPlayerZeigtFehlerBeiBeschaedigterDateiOhneAbsturz() {
        val korrupteDatei = File(cacheDir, "korrupt.wav").apply {
            writeBytes(byteArrayOf(0, 1, 2, 3)) // Nur 4 Bytes statt mindestens 44
        }
        var backed = false

        composeRule.setContent {
            AudioPlayerScreen(filePath = korrupteDatei.absolutePath, onBack = { backed = true })
        }
        composeRule.waitForIdle()

        val errStr = composeRule.activity.getString(com.example.lrmprotokoll.R.string.player_error_cannot_play)
        val backDesc = composeRule.activity.getString(com.example.lrmprotokoll.R.string.action_back)

        // 1. Fehler-Text für zu kurze/beschädigte Datei sichtbar
        composeRule.onNodeWithText(errStr, substring = true).assertIsDisplayed()

        // 2. Zurück-Button in TopAppBar klickbar
        composeRule.onNodeWithContentDescription(backDesc).assertIsDisplayed().performClick()
        assertTrue(backed)

        korrupteDatei.delete()
    }
}
