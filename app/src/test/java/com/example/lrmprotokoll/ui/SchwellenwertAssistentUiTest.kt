package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.audio.AudioRecordingService
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * PROMPT_M10_FUNKTIONEN.md F1 (Schwellenwert-Assistent): der Live-Pegel und die beiden
 * Vorschlags-Knöpfe hängen an [AudioRecordingService.audioAufnahmeAktiv]/[AudioRecordingService.currentMicDb]
 * (statische StateFlows, dieselbe Technik wie [ServiceControlComposeTest]) - kein Mikrofon unter
 * Robolectric nötig, um das Verhalten zu belegen. Setzt und resetzt die StateFlows selbst, weil
 * sie companion-object-weit geteilt sind und sonst in andere Tests hineinwirken würden.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h480dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SchwellenwertAssistentUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun ohneLaufendeUeberwachungGibtEsKeinenLiveWertUndDieKnoepfeSindGesperrt() {
        ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        AudioRecordingService.testSetzeAudioAufnahmeAktiv(false)
        AudioRecordingService.testSetzeCurrentMicDb(null)

        composeRule.setContent { SettingsScreen(onBack = {}) }

        val titel = composeRule.activity.getString(R.string.settings_section_thresholds)
        composeRule.onNodeWithText(titel, substring = true).performClick()

        val hinweis = composeRule.activity.getString(R.string.settings_threshold_no_live_level)
        composeRule.onNodeWithText(hinweis).performScrollTo().assertIsDisplayed()

        val knopfText = composeRule.activity.getString(R.string.settings_threshold_use_current)
        composeRule.onNodeWithText(knopfText).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun knopfAufAktuellemPegelUebernimmtDenLiveWertAlsSchwelle() {
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        AudioRecordingService.testSetzeAudioAufnahmeAktiv(true)
        AudioRecordingService.testSetzeCurrentMicDb(52.0)

        composeRule.setContent { SettingsScreen(onBack = {}) }

        val titel = composeRule.activity.getString(R.string.settings_section_thresholds)
        composeRule.onNodeWithText(titel, substring = true).performClick()

        val knopfText = composeRule.activity.getString(R.string.settings_threshold_use_current)
        composeRule.onNodeWithText(knopfText).performScrollTo().assertIsDisplayed().performClick()

        assertEquals(52.0f, app.container.settingsManager.dbThreshold, 0.01f)

        AudioRecordingService.testSetzeAudioAufnahmeAktiv(false)
        AudioRecordingService.testSetzeCurrentMicDb(null)
    }

    @Test
    fun knopfMitSicherheitsabstandAddiertFuenfDbZumLiveWert() {
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        AudioRecordingService.testSetzeAudioAufnahmeAktiv(true)
        AudioRecordingService.testSetzeCurrentMicDb(52.0)

        composeRule.setContent { SettingsScreen(onBack = {}) }

        val titel = composeRule.activity.getString(R.string.settings_section_thresholds)
        composeRule.onNodeWithText(titel, substring = true).performClick()

        val knopfText = composeRule.activity.getString(R.string.settings_threshold_use_current_plus_5)
        composeRule.onNodeWithText(knopfText).performScrollTo().assertIsDisplayed().performClick()

        assertEquals(57.0f, app.container.settingsManager.dbThreshold, 0.01f)

        AudioRecordingService.testSetzeAudioAufnahmeAktiv(false)
        AudioRecordingService.testSetzeCurrentMicDb(null)
    }
}
