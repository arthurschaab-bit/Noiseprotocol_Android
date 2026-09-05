package com.example.lrmprotokoll.ui

import android.app.Activity
import android.app.Instrumentation
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.anyIntent
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.alert.Alert
import com.example.lrmprotokoll.alert.AlertKind
import com.example.lrmprotokoll.alert.AlertReason
import com.example.lrmprotokoll.alert.local.LocalNotificationAlertChannel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class SettingsScreenInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: LaermprotokollApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun settingsScreenZeigtTitelUndScrolltBisZumEnde() {
        var backed = false
        composeRule.setContent { SettingsScreen(onBack = { backed = true }) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(composeRule.activity.getString(com.example.lrmprotokoll.R.string.nav_settings)).assertIsDisplayed()
        composeRule.onNodeWithTag(BILDSCHIRM_ENDE_TAG).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription(composeRule.activity.getString(com.example.lrmprotokoll.R.string.action_back))
            .assertIsDisplayed().performClick()
        assertTrue(backed)
    }

    @Test
    fun settingsScreenErlaubtAbtastrateAuswahlUndSchalterBedienung() {
        composeRule.setContent { SettingsScreen(onBack = {}) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("btn_settings_mode_pro").performClick()
        val secThresholds = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_section_thresholds)
        composeRule.onNodeWithText(secThresholds, substring = true).performClick()
        composeRule.onNodeWithText(composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_sample_rate_16k), substring = true)
            .performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithText(composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_sample_rate_44k), substring = true)
            .performScrollTo().assertIsDisplayed().performClick()

        val secAi = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_ai_title)
        composeRule.onAllNodesWithText(secAi, substring = true).onFirst().performScrollTo().performClick()
        composeRule.onAllNodesWithText(secAi, substring = true).onFirst().assertIsDisplayed()
    }

    @Test
    fun schwellenSliderPersistiertMinimumUndMaximum() {
        val settingsManager = app.container.settingsManager
        val oldPro = settingsManager.isProMode
        val oldThreshold = settingsManager.dbThreshold
        try {
            settingsManager.isProMode = true
            settingsManager.dbThreshold = 65f

            composeRule.setContent { SettingsScreen(onBack = {}) }
            composeRule.waitForIdle()
            val sectionTitle = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_section_thresholds)
            composeRule.onNodeWithText(sectionTitle, substring = true).performClick()

            val slider = composeRule.onNodeWithTag("slider_db_threshold").performScrollTo().assertIsDisplayed()
            slider.performTouchInput { swipe(start = center, end = centerLeft, durationMillis = 500L) }
            composeRule.waitForIdle()
            assertEquals(30f, settingsManager.dbThreshold, 0.6f)

            slider.performTouchInput { swipe(start = centerLeft, end = centerRight, durationMillis = 500L) }
            composeRule.waitForIdle()
            assertEquals(100f, settingsManager.dbThreshold, 0.6f)
        } finally {
            settingsManager.dbThreshold = oldThreshold
            settingsManager.isProMode = oldPro
        }
    }

    @Test
    fun ntfyErzeugtBeimErstenAktivierenAutomatischEinTopicUndZeigtEsAn() {
        val settingsManager = app.container.settingsManager
        val oldPro = settingsManager.isProMode
        val oldAlerting = settingsManager.alarmierungAktiv
        val oldNtfyActive = settingsManager.ntfyAktiv
        val oldTopic = settingsManager.ntfyTopic
        try {
            settingsManager.isProMode = true
            settingsManager.alarmierungAktiv = true
            settingsManager.ntfyAktiv = false
            settingsManager.ntfyTopic = ""

            composeRule.setContent { SettingsScreen(onBack = {}) }
            composeRule.waitForIdle()
            val title = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_alerting_title)
            composeRule.onNodeWithText(title, substring = true).performScrollTo().performClick()
            composeRule.onNodeWithTag("switch_ntfy_enabled").performScrollTo().assertIsDisplayed().performClick()

            composeRule.waitUntil(timeoutMillis = 5_000L) { settingsManager.ntfyTopic.isNotBlank() }
            val generatedTopic = settingsManager.ntfyTopic
            composeRule.onNodeWithTag("input_ntfy_topic").performScrollTo().assertTextContains(generatedTopic)
        } finally {
            settingsManager.ntfyTopic = oldTopic
            settingsManager.ntfyAktiv = oldNtfyActive
            settingsManager.alarmierungAktiv = oldAlerting
            settingsManager.isProMode = oldPro
        }
    }

    @Test
    fun ntfyBelaesstVorhandenesTopicBeimAktivierenUnveraendert() {
        val settingsManager = app.container.settingsManager
        val oldPro = settingsManager.isProMode
        val oldAlerting = settingsManager.alarmierungAktiv
        val oldNtfyActive = settingsManager.ntfyAktiv
        val oldTopic = settingsManager.ntfyTopic
        val existingTopic = "bestehendes-test-topic"
        try {
            settingsManager.isProMode = true
            settingsManager.alarmierungAktiv = true
            settingsManager.ntfyAktiv = false
            settingsManager.ntfyTopic = existingTopic

            composeRule.setContent { SettingsScreen(onBack = {}) }
            composeRule.waitForIdle()
            val title = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_alerting_title)
            composeRule.onNodeWithText(title, substring = true).performScrollTo().performClick()
            composeRule.onNodeWithTag("switch_ntfy_enabled").performScrollTo().assertIsDisplayed().performClick()

            composeRule.waitForIdle()
            assertEquals(existingTopic, settingsManager.ntfyTopic)
            composeRule.onNodeWithTag("input_ntfy_topic").performScrollTo().assertTextContains(existingTopic)
        } finally {
            settingsManager.ntfyTopic = oldTopic
            settingsManager.ntfyAktiv = oldNtfyActive
            settingsManager.alarmierungAktiv = oldAlerting
            settingsManager.isProMode = oldPro
        }
    }

    @Test
    fun systemIntentsFuerAkkuOptimierungUndExakteAlarmeSindImmerGeprueft() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        Intents.init()
        try {
            intending(anyIntent()).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
            val context = ApplicationProvider.getApplicationContext<LaermprotokollApp>()

            composeRule.setContent {
                OemDeviceHelperCard(
                    notificationPermissionOverride = true,
                    exactAlarmPermissionOverride = false,
                    batteryOptimizedOverride = true,
                )
            }
            composeRule.waitForIdle()

            composeRule.onNodeWithTag(OEM_BATTERY_OPTIMIZATION_BUTTON_TAG).assertIsDisplayed().performClick()
            intended(hasAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS))
            intended(hasData(Uri.parse("package:${context.packageName}")))

            composeRule.onNodeWithTag(OEM_EXACT_ALARM_BUTTON_TAG).assertIsDisplayed().performClick()
            intended(hasAction(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
            intended(hasData(Uri.parse("package:${context.packageName}")))
        } finally {
            Intents.release()
        }
    }

    @Test
    fun lokaleTestMeldungOhnePostNotificationsCrashtNicht() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val context = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.revokeRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)

        try {
            val channel = LocalNotificationAlertChannel(context, context.container.settingsManager)
            assertFalse("Kanal muss ohne POST_NOTIFICATIONS als nicht verfügbar gelten", channel.isAvailable)

            runBlocking {
                channel.send(
                    Alert(
                        alertId = 0,
                        kind = AlertKind.TEST,
                        reason = AlertReason.DISCONNECTED,
                        since = Instant.now(),
                        message = "Test-Meldung ohne Benachrichtigungsberechtigung",
                    )
                )
            }
            // Wenn send() zurückkehrt, wurde kein SecurityException-Crash bis in den Test propagiert.
        } finally {
            automation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
            LocalNotificationAlertChannel.stoppeAlarmTon(context)
        }
    }
}
