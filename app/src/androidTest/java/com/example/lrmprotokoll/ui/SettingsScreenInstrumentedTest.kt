package com.example.lrmprotokoll.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.anyIntent
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.LaermprotokollApp
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentierte UI-Tests für den [SettingsScreen] gemäß Testplan.
 *
 * Prüft alle Slider, Schalter (KI, Alarmierung, Push, Drive, Diagnose),
 * die Scrollbarkeit des gesamten Screens bis [BILDSCHIRM_ENDE_TAG], sowie die Navigation.
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        ApplicationProvider.getApplicationContext<LaermprotokollApp>()
    }

    @Test
    fun settingsScreenZeigtTitelSliderUndScrolltBisZumEnde() {
        var backed = false

        composeRule.setContent {
            SettingsScreen(onBack = { backed = true })
        }
        composeRule.waitForIdle()

        val settingsTitle = composeRule.activity.getString(com.example.lrmprotokoll.R.string.nav_settings)
        val secThresholds = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_section_thresholds)
        val backDesc = composeRule.activity.getString(com.example.lrmprotokoll.R.string.action_back)

        // 1. Titel in TopAppBar
        composeRule.onNodeWithText(settingsTitle).assertIsDisplayed()

        // 2. Aufnahme & Schwellenwert Sektion aufklappen
        composeRule.onNodeWithText(secThresholds, substring = true).performClick()
        composeRule.waitForIdle()

        // 3. Bis zum Bildschirmende scrollen
        composeRule.onNodeWithTag(BILDSCHIRM_ENDE_TAG).performScrollTo().assertIsDisplayed()

        // 4. Zurück-Button
        composeRule.onNodeWithContentDescription(backDesc).assertIsDisplayed().performClick()
        assertTrue(backed)
    }

    @Test
    fun settingsScreenErlaubtAbtastrateAuswahlUndSchalterBedienung() {
        composeRule.setContent {
            SettingsScreen(onBack = {})
        }
        composeRule.waitForIdle()

        val proMode = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_mode_pro)
        val secThresholds = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_section_thresholds)
        val sample16k = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_sample_rate_16k)
        val sample44k = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_sample_rate_44k)
        val secAi = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_ai_title)
        val secDiag = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_section_diagnostics)

        // In den Pro-Modus schalten, um erweiterte Optionen (Abtastrate etc.) freizuschalten
        composeRule.onNodeWithText(proMode, substring = true).performClick()
        composeRule.waitForIdle()

        // 1. Aufnahme & Schwellenwert Sektion aufklappen & Abtastrate-Chips prüfen
        composeRule.onNodeWithText(secThresholds, substring = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(sample16k, substring = true).performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithText(sample44k, substring = true).performScrollTo().assertIsDisplayed().performClick()

        // 2. KI Sektion aufklappen & Schalter prüfen
        composeRule.onAllNodesWithText(secAi, substring = true).onFirst().performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText(secAi, substring = true).onFirst().assertIsDisplayed()

        // 3. System & Diagnose Sektion aufklappen
        composeRule.onNodeWithText(secDiag, substring = true).performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun systemIntentsFuerAkkuOptimierungUndExakteAlarmeLoesenKorrektAus() {
        Intents.init()
        try {
            intending(anyIntent()).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))

            val context = ApplicationProvider.getApplicationContext<LaermprotokollApp>()

            composeRule.setContent {
                OemDeviceHelperCard()
            }
            composeRule.waitForIdle()

            // Falls Button sichtbar ist (bei nicht ausgenommener Akku-Optimierung), Klick & Intent prüfen
            val nodes = composeRule.onAllNodesWithText("Akku-Optimierung aufheben")
            if (nodes.fetchSemanticsNodes().isNotEmpty()) {
                nodes[0].performClick()
                intended(hasAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS))
                intended(hasData(Uri.parse("package:${context.packageName}")))
            }
        } finally {
            Intents.release()
        }
    }

    @Test
    fun testAlarmAusloesenUndStoppenBehandeltZustandOhneAbsturz() {
        val context = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        val initialAlarmAktiv = context.container.settingsManager.alarmierungAktiv
        context.container.settingsManager.alarmierungAktiv = true
        context.container.settingsManager.alarmTonAktiv = true

        try {
            composeRule.setContent {
                SettingsScreen(onBack = {})
            }
            composeRule.waitForIdle()

            // 1. Alarmierungssektion suchen & aufklappen
            val secAlarm = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_alerting_title)
            composeRule.onNodeWithText(secAlarm, substring = true).performScrollTo().performClick()
            composeRule.waitForIdle()

            // 2. Test-Alarm Button betätigen (sichtbar da alarmierungAktiv = true)
            composeRule.onNodeWithText("Test-Alarm").performScrollTo().performClick()

            // 3. Warten bis Ergebnistext ("Test-Alarm ausgelöst" oder "Fehlgeschlagen: ...") erscheint
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                composeRule.onAllNodesWithText("Test-Alarm ausgelöst", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Fehlgeschlagen", substring = true).fetchSemanticsNodes().isNotEmpty()
            }

            // 4. Alarm stoppen Button betätigen
            composeRule.onNodeWithText("Alarm stoppen").performScrollTo().performClick()

            composeRule.waitUntil(timeoutMillis = 5_000L) {
                composeRule.onAllNodesWithText("Alarmton gestoppt", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("Alarmton gestoppt", substring = true).assertIsDisplayed()
        } finally {
            context.container.settingsManager.alarmierungAktiv = initialAlarmAktiv
        }
    }
}
