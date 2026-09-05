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
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.anyIntent
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.alert.Alert
import com.example.lrmprotokoll.alert.AlertKind
import com.example.lrmprotokoll.alert.AlertReason
import com.example.lrmprotokoll.alert.local.LocalNotificationAlertChannel
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: LaermprotokollApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        LocalNotificationAlertChannel.stoppeAlarmTon(app)
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
            slider.performTouchInput { swipeLeft() }
            composeRule.waitForIdle()
            assertEquals(30f, settingsManager.dbThreshold, 0.6f)

            slider.performTouchInput { swipeRight() }
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
        assumeTrue("Exakte Alarme gibt es erst ab Android 12", Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)

        Intents.init()
        try {
            intending(anyIntent()).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
            val context = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
            val packageUri = Uri.parse("package:${context.packageName}")

            composeRule.setContent {
                OemDeviceHelperCard(
                    notificationPermissionOverride = true,
                    exactAlarmPermissionOverride = false,
                    batteryOptimizedOverride = true,
                )
            }
            composeRule.waitForIdle()

            composeRule.onNodeWithTag(OEM_BATTERY_OPTIMIZATION_BUTTON_TAG).assertIsDisplayed().performClick()
            intended(
                allOf(
                    hasAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS),
                    hasData(packageUri),
                )
            )

            composeRule.onNodeWithTag(OEM_EXACT_ALARM_BUTTON_TAG).assertIsDisplayed().performClick()
            intended(
                allOf(
                    hasAction(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM),
                    hasData(packageUri),
                )
            )
        } finally {
            Intents.release()
        }
    }

    @Test
    fun testAlarmAusloesenUndStoppenBehandeltZustandOhneAbsturz() {
        val settingsManager = app.container.settingsManager
        val initialAlarmAktiv = settingsManager.alarmierungAktiv
        val initialAlarmTonAktiv = settingsManager.alarmTonAktiv
        settingsManager.alarmierungAktiv = true
        settingsManager.alarmTonAktiv = true

        try {
            composeRule.setContent { SettingsScreen(onBack = {}) }
            composeRule.waitForIdle()

            val secAlarm = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_alerting_title)
            composeRule.onNodeWithText(secAlarm, substring = true).performScrollTo().performClick()
            composeRule.waitForIdle()

            composeRule.onNodeWithText("Test-Alarm").performScrollTo().assertIsDisplayed().performClick()
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                composeRule.onAllNodesWithText("Test-Alarm ausgelöst", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("Test-Alarm ausgelöst", substring = true).assertIsDisplayed()

            composeRule.onNodeWithText("Alarm stoppen").performScrollTo().assertIsDisplayed().performClick()
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                composeRule.onAllNodesWithText("Alarmton gestoppt", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("Alarmton gestoppt", substring = true).assertIsDisplayed()
        } finally {
            settingsManager.alarmTonAktiv = initialAlarmTonAktiv
            settingsManager.alarmierungAktiv = initialAlarmAktiv
            LocalNotificationAlertChannel.stoppeAlarmTon(app)
        }
    }

    @Test
    fun lokaleTestMeldungOhnePostNotificationsCrashtNicht() {
        assumeTrue(
            "POST_NOTIFICATIONS existiert erst ab Android 13",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
        )

        val context = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        val channel = LocalNotificationAlertChannel(
            context = context,
            settings = context.container.settingsManager,
            notificationPermissionOverride = false,
        )

        assertFalse("Kanal muss ohne POST_NOTIFICATIONS als nicht verfügbar gelten", channel.isAvailable)

        val result = runBlocking {
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

        assertTrue(
            "Fehlende Benachrichtigungsberechtigung darf Ton/Vibration des lokalen Alarmwegs nicht abbrechen",
            result.isSuccess,
        )
        LocalNotificationAlertChannel.stoppeAlarmTon(context)
    }
}
