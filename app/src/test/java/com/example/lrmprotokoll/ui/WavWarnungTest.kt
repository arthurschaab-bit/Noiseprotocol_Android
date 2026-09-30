package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regression zu einer Luecke, die beim Aufraeumen entstanden ist (Owner-Meldung 29.09.2026).
 *
 * Bis PR #229 warnte das Cockpit-Menue beim Abschalten von `recordWavAudio`: "Neue
 * Laermereignisse werden dann ohne WAV-Beweisdatei gespeichert". F-22 hat diesen doppelten
 * Einstieg entfernt, weil dieselbe Einstellung schon einen Schalter in den Einstellungen hat -
 * nur hatte *der* nie eine Warnung. Nach dem Merge stand die Einstellung damit ohne jeden
 * Hinweis da, obwohl sie entscheidet, ob ein Laermereignis ueberhaupt eine Beweisdatei bekommt.
 *
 * Owner-Wahl 29.09.2026: **Hinweis statt Bestaetigungsdialog.** Ein Dialog haette den Vertrag
 * gebrochen, den `SettingsScreenInstrumentedTest` festhaelt (ein Klick schaltet sofort), und er
 * waere nur im Moment des Abschaltens sichtbar gewesen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "de-rDE-w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WavWarnungTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: LaermprotokollApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.container.settingsManager.recordWavAudio = true
    }

    /**
     * Der WAV-Schalter liegt im Abschnitt "Schwellenwerte & Audio", und der ist **zugeklappt**.
     * Genau dieser Fundort hat den ersten Geraetetest zu PR #216 gekostet; ohne das Aufklappen
     * ist der Schalter nicht einmal im Semantik-Baum.
     */
    private fun oeffneAbschnittSchwellenwerte() {
        composeRule.setContent { SettingsScreen(onBack = {}) }
        composeRule.onNodeWithText("Schwellenwerte & Audio").performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    /** Bei eingeschalteter WAV-Aufzeichnung gibt es nichts zu warnen. */
    @Test
    fun mitWavAufzeichnungStehtKeinHinweisDa() {
        oeffneAbschnittSchwellenwerte()

        composeRule.onNodeWithTag("switch_record_wav_audio").performScrollTo()
        composeRule.onNodeWithTag("hinweis_record_wav_aus").assertDoesNotExist()
    }

    /**
     * Der Kern: Abschalten schaltet weiterhin **sofort** (kein Dialog), und danach steht der
     * Hinweis da. Faellt dieser Test, ist die Warnung wieder verschwunden.
     */
    @Test
    fun abschaltenSchaltetSofortUndZeigtDenHinweis() {
        oeffneAbschnittSchwellenwerte()

        composeRule.onNodeWithTag("switch_record_wav_audio").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("hinweis_record_wav_aus").performScrollTo().assertIsDisplayed()
    }

    /**
     * Der Hinweis haengt am Zustand, nicht am Moment des Umschaltens: Wer die Einstellungen
     * spaeter erneut oeffnet, sieht ihn weiterhin. Genau das kann ein Bestaetigungsdialog nicht,
     * und genau das beantwortet die Frage "warum hat meine letzte Messung keinen Ton?".
     */
    @Test
    fun derHinweisStehtAuchBeimSpaeterenOeffnenNochDa() {
        app.container.settingsManager.recordWavAudio = false

        oeffneAbschnittSchwellenwerte()

        composeRule.onNodeWithTag("hinweis_record_wav_aus").performScrollTo().assertIsDisplayed()
    }
}
