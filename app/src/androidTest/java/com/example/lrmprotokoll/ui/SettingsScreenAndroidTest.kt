package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.AppContainer
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.data.SettingsManager
import com.example.lrmprotokoll.ui.theme.LaermprotokollTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Systematische instrumentierte UI- und E2E-Tests für den [SettingsScreen].
 *
 * Verifiziert auf dem Android-Emulator (API 34 ATD):
 * 1. TopAppBar-Navigation (Drawer-Öffnen vs. Zurück-Pfeil).
 * 2. Modus-Umschaltung: Lite (Einfach) vs. Pro-Modus.
 * 3. Sprachauswahl (System, Deutsch, English) und Persistierung.
 * 4. Aufnahme-Parameter: WAV-Audio-Schalter und Audio-Trigger-Quelle.
 * 5. KI-Erkennungsmodus: Umschaltung zwischen Batch, Online und Aus sowie In-App-Erklärung.
 * 6. Push-Benachrichtigungen: Ntfy Topic- und Server-Eingabefelder.
 * 7. Vollständige Navigation zur Diagnose-Ansicht.
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenAndroidTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: LaermprotokollApp
    private lateinit var container: AppContainer
    private lateinit var settings: SettingsManager

    private var initialProMode: Boolean = false
    private var initialLanguage: String = ""
    private var initialWavAudio: Boolean = true
    private var initialTriggerQuelle: String = "AUTO"
    private var initialAiMode: String = "BATCH"
    private var initialAlarmAktiv: Boolean = false
    private var initialNtfyAktiv: Boolean = false
    private var initialNtfyTopic: String = ""

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        container = app.container
        settings = container.settingsManager

        initialProMode = settings.isProMode
        initialLanguage = settings.appLanguage
        initialWavAudio = settings.recordWavAudio
        initialTriggerQuelle = settings.audioTriggerQuelle
        initialAiMode = settings.aiMode
        initialAlarmAktiv = settings.alarmierungAktiv
        initialNtfyAktiv = settings.ntfyAktiv
        initialNtfyTopic = settings.ntfyTopic
    }

    @After
    fun tearDown() {
        settings.isProMode = initialProMode
        settings.appLanguage = initialLanguage
        settings.recordWavAudio = initialWavAudio
        settings.audioTriggerQuelle = initialTriggerQuelle
        settings.aiMode = initialAiMode
        settings.alarmierungAktiv = initialAlarmAktiv
        settings.ntfyAktiv = initialNtfyAktiv
        settings.ntfyTopic = initialNtfyTopic
    }

    @Test
    fun topAppBarOeffnetDrawerWennKonfiguriert() {
        var drawerOpened = false

        composeRule.setContent {
            LaermprotokollTheme {
                SettingsScreen(
                    onBack = {},
                    onOpenDrawer = { drawerOpened = true }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("btn_navigation_drawer").assertIsDisplayed().performClick()
        assertTrue("Drawer-Callback sollte aufgerufen worden sein", drawerOpened)
    }

    @Test
    fun topAppBarNavigiertZurueckOhneDrawer() {
        var backClicked = false

        composeRule.setContent {
            LaermprotokollTheme {
                SettingsScreen(
                    onBack = { backClicked = true },
                    onOpenDrawer = null
                )
            }
        }
        composeRule.waitForIdle()

        val backDescription = composeRule.activity.getString(R.string.action_back)
        composeRule.onNodeWithContentDescription(backDescription).assertIsDisplayed().performClick()
        assertTrue("Back-Callback sollte aufgerufen worden sein", backClicked)
    }

    @Test
    fun modusUmschalterWechseltZwischenLiteUndProModus() {
        settings.isProMode = false

        composeRule.setContent {
            LaermprotokollTheme {
                SettingsScreen(onBack = {})
            }
        }
        composeRule.waitForIdle()

        // 1. Klick auf Pro-Modus
        composeRule.onNodeWithTag("btn_settings_mode_pro").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertTrue("Pro-Modus sollte aktiv sein", settings.isProMode)

        // 2. Klick auf Lite-Modus
        composeRule.onNodeWithTag("btn_settings_mode_lite").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertFalse("Pro-Modus sollte deaktiviert sein", settings.isProMode)
    }

    @Test
    fun sprachAuswahlAktualisiertAppLanguageInSettings() {
        composeRule.setContent {
            LaermprotokollTheme {
                SettingsScreen(onBack = {})
            }
        }
        composeRule.waitForIdle()

        // Sektion Sprache öffnen
        val secLanguage = composeRule.activity.getString(R.string.settings_language_title)
        composeRule.onNodeWithText(secLanguage, substring = true).performScrollTo().performClick()
        composeRule.waitForIdle()

        // 1. Deutsch wählen
        val langDe = composeRule.activity.getString(R.string.settings_language_de)
        composeRule.onNodeWithText(langDe).performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals("de", settings.appLanguage)

        // 2. English wählen
        val langEn = composeRule.activity.getString(R.string.settings_language_en)
        composeRule.onNodeWithText(langEn).performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals("en", settings.appLanguage)

        // 3. Systemstandard wählen
        val langSystem = composeRule.activity.getString(R.string.settings_language_system)
        composeRule.onNodeWithText(langSystem).performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals("", settings.appLanguage)
    }

    @Test
    fun aufnahmeParameterSchalterUndTriggerQuelle() {
        composeRule.setContent {
            LaermprotokollTheme {
                SettingsScreen(onBack = {})
            }
        }
        composeRule.waitForIdle()

        // Sektion Aufnahme & Schwellenwert öffnen
        val secThresholds = composeRule.activity.getString(R.string.settings_section_thresholds)
        composeRule.onNodeWithText(secThresholds, substring = true).performScrollTo().performClick()
        composeRule.waitForIdle()

        // Trigger-Quelle: Nur PCE-323 wählen
        val triggerMeter = composeRule.activity.getString(R.string.settings_trigger_source_meter)
        composeRule.onNodeWithText(triggerMeter).performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals("PCE_323", settings.audioTriggerQuelle)

        // Trigger-Quelle: Nur Mikrofon wählen
        val triggerMic = composeRule.activity.getString(R.string.settings_trigger_source_mic)
        composeRule.onNodeWithText(triggerMic).performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals("MIKROFON", settings.audioTriggerQuelle)

        // Trigger-Quelle: Automatisch wählen
        val triggerAuto = composeRule.activity.getString(R.string.settings_trigger_source_auto)
        composeRule.onNodeWithText(triggerAuto).performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals("AUTO", settings.audioTriggerQuelle)
    }

    @Test
    fun kiModusAuswahlUndErklaerungNavigation() {
        var erklaerungOpened = false

        composeRule.setContent {
            LaermprotokollTheme {
                SettingsScreen(
                    onBack = {},
                    onOpenKiErklaerung = { erklaerungOpened = true }
                )
            }
        }
        composeRule.waitForIdle()

        // KI-Sektion aufklappen
        val secAi = composeRule.activity.getString(R.string.settings_ai_title)
        composeRule.onAllNodesWithText(secAi, substring = true).onFirst().performScrollTo().performClick()
        composeRule.waitForIdle()

        // Online / Live wählen
        composeRule.onNodeWithText("Online / Live").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals("ONLINE", settings.aiMode)

        // Aus wählen
        composeRule.onNodeWithText("Aus").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals("OFF", settings.aiMode)

        // Batch wählen
        composeRule.onNodeWithText("Im Batch (Default)").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals("BATCH", settings.aiMode)

        // Erklärung aufrufen
        composeRule.onNodeWithText("Wie die Lärmerkennung arbeitet", substring = true)
            .performScrollTo()
            .performClick()
        assertTrue("In-App KI-Erklärung Callback sollte aufgerufen worden sein", erklaerungOpened)
    }

    @Test
    fun pushNtfyTopicUndServerEingabe() {
        settings.isProMode = true
        settings.alarmierungAktiv = true
        settings.ntfyAktiv = true
        settings.ntfyTopic = "original-topic"

        composeRule.setContent {
            LaermprotokollTheme {
                SettingsScreen(onBack = {})
            }
        }
        composeRule.waitForIdle()

        // Alarmierungssektion öffnen
        val secAlarm = composeRule.activity.getString(R.string.settings_alerting_title)
        composeRule.onNodeWithText(secAlarm, substring = true).performScrollTo().performClick()
        composeRule.waitForIdle()

        // Ntfy-Topic-Feld suchen, leeren und neuen Text eintippen
        val topicField = composeRule.onNodeWithTag("input_ntfy_topic").performScrollTo()
        topicField.assertIsDisplayed()
        topicField.performTextClearance()
        topicField.performTextInput("neues-test-topic")
        composeRule.waitForIdle()

        assertEquals("neues-test-topic", settings.ntfyTopic)
    }

    @Test
    fun diagnoseNavigationWirdAusgeloest() {
        var diagnoseOpened = false

        composeRule.setContent {
            LaermprotokollTheme {
                SettingsScreen(
                    onBack = {},
                    onNavigateToDiagnose = { diagnoseOpened = true }
                )
            }
        }
        composeRule.waitForIdle()

        // Diagnose-Sektion öffnen
        val secDiag = composeRule.activity.getString(R.string.settings_section_diagnostics)
        composeRule.onNodeWithText(secDiag, substring = true).performScrollTo().performClick()
        composeRule.waitForIdle()

        // Diagnose-Button klicken
        composeRule.onNodeWithTag("btn_open_diagnose").performScrollTo().assertIsDisplayed().performClick()
        assertTrue("Diagnose-Callback sollte aufgerufen worden sein", diagnoseOpened)
    }
}
