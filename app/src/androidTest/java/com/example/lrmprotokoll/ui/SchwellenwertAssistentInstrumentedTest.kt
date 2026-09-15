package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.audio.AudioRecordingService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Echtes Geraete-Pendant zu [SchwellenwertAssistentUiTest] (Robolectric, app/src/test) - Teil der
 * Bestandsaufnahme nach dem Datumsbereich-Dialog-Bug (Owner-Auftrag 15.09.2026).
 * PROMPT_M10_FUNKTIONEN.md F1: der Live-Pegel und die Vorschlags-Knoepfe haengen an statischen
 * StateFlows (kein echtes Mikrofon noetig).
 */
@RunWith(AndroidJUnit4::class)
class SchwellenwertAssistentInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @After
    fun aufraeumen() {
        AudioRecordingService.testSetzeAudioAufnahmeAktiv(false)
        AudioRecordingService.testSetzeCurrentMicDb(null)
    }

    @Test
    fun ohneLaufendeUeberwachungGibtEsKeinenLiveWertUndDieKnoepfeSindGesperrt() {
        AudioRecordingService.testSetzeAudioAufnahmeAktiv(false)
        AudioRecordingService.testSetzeCurrentMicDb(null)

        composeRule.setContent { SettingsScreen(onBack = {}) }
        composeRule.waitForIdle()

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
        composeRule.waitForIdle()

        val titel = composeRule.activity.getString(R.string.settings_section_thresholds)
        composeRule.onNodeWithText(titel, substring = true).performClick()

        val knopfText = composeRule.activity.getString(R.string.settings_threshold_use_current)
        composeRule.onNodeWithText(knopfText).performScrollTo().assertIsDisplayed().performClick()

        assertEquals(52.0f, app.container.settingsManager.dbThreshold, 0.01f)
    }

    @Test
    fun knopfMitSicherheitsabstandAddiertFuenfDbZumLiveWert() {
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        AudioRecordingService.testSetzeAudioAufnahmeAktiv(true)
        AudioRecordingService.testSetzeCurrentMicDb(52.0)

        composeRule.setContent { SettingsScreen(onBack = {}) }
        composeRule.waitForIdle()

        val titel = composeRule.activity.getString(R.string.settings_section_thresholds)
        composeRule.onNodeWithText(titel, substring = true).performClick()

        val knopfText = composeRule.activity.getString(R.string.settings_threshold_use_current_plus_5)
        composeRule.onNodeWithText(knopfText).performScrollTo().assertIsDisplayed().performClick()

        assertEquals(57.0f, app.container.settingsManager.dbThreshold, 0.01f)
    }
}
