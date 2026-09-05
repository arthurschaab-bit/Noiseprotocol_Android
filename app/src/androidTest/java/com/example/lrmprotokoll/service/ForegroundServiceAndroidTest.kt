package com.example.lrmprotokoll.service

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.audio.ACTION_STOP_AUDIO_RECORDING
import com.example.lrmprotokoll.audio.AudioRecordingService
import com.example.lrmprotokoll.audio.EXTRA_START_AUDIO_MONITORING
import com.example.lrmprotokoll.ui.END_MEASUREMENT_BUTTON_TAG
import com.example.lrmprotokoll.ui.LiveCockpitCard
import com.example.lrmprotokoll.ui.START_MEASUREMENT_BUTTON_TAG
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentierte Tests für den Foreground-Service-Lebenszyklus und die Ongoing-Notification auf API 34.
 *
 * Validiert:
 * 1. Start über UI (LiveCockpitCard) startet den Foreground Service, AudioRecordingService.laeuft wird true.
 * 2. Ongoing-Notification (ID 1) ist im System aktiv (NotificationManager.activeNotifications).
 * 3. Stoppen des Dienstes über UI beendet den Service, AudioRecordingService.laeuft wird false und die Notification verschwindet.
 */
@RunWith(AndroidJUnit4::class)
class ForegroundServiceAndroidTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext
        notificationManager = context.getSystemService(NotificationManager::class.java)

        // Runtime-Berechtigungen auf API 34 explizit erteilen
        val uiAutomation = instrumentation.uiAutomation
        uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }

        // Falls vorherige Tests noch einen Service hinterlassen haben, sauber beenden
        stoppeServiceFallsAktiv()
    }

    @After
    fun tearDown() {
        stoppeServiceFallsAktiv()
    }

    private fun stoppeServiceFallsAktiv() {
        try {
            val stopIntent = Intent(context, AudioRecordingService::class.java).apply {
                action = ACTION_STOP_AUDIO_RECORDING
            }
            context.startService(stopIntent)
            context.stopService(Intent(context, AudioRecordingService::class.java))
        } catch (_: Exception) {}

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            !AudioRecordingService.laeuft.value &&
                notificationManager.activeNotifications.none { it.id == 1 }
        }
    }

    @Test
    fun foregroundServiceStartetUndStopptUeberUiUndVerwaltetOngoingNotification() {
        composeRule.setContent {
            LiveCockpitCard()
        }
        composeRule.waitForIdle()

        // 1. Start-Button muss sichtbar und klickbar sein
        composeRule.onNodeWithTag(START_MEASUREMENT_BUTTON_TAG).assertIsDisplayed().performClick()

        // 2. Warten bis der Service gestartet ist und die Notification aktiv ist
        composeRule.waitUntil(timeoutMillis = 7_000L) {
            AudioRecordingService.laeuft.value &&
                notificationManager.activeNotifications.any { it.id == 1 }
        }

        assertTrue("AudioRecordingService.laeuft muss true sein", AudioRecordingService.laeuft.value)

        val activeNotification = notificationManager.activeNotifications.firstOrNull { it.id == 1 }
        assertNotNull("Ongoing Foreground-Notification mit ID 1 muss existieren", activeNotification)
        assertTrue("Notification muss als ongoing markiert sein", activeNotification!!.isOngoing)
        assertEquals("noise_monitoring_channel", activeNotification.notification.channelId)

        // 3. Stoppen über UI (Beenden-Button)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(END_MEASUREMENT_BUTTON_TAG).assertIsDisplayed().performClick()

        // 4. Warten bis Service beendet und Notification entfernt ist
        composeRule.waitUntil(timeoutMillis = 7_000L) {
            !AudioRecordingService.laeuft.value &&
                notificationManager.activeNotifications.none { it.id == 1 }
        }

        assertFalse("AudioRecordingService.laeuft muss nach Beenden false sein", AudioRecordingService.laeuft.value)
        assertTrue(
            "Ongoing Notification muss nach Beenden des Dienstes verschwinden",
            notificationManager.activeNotifications.none { it.id == 1 }
        )
    }

    @Test
    fun foregroundServiceStartDirectIntentLebenszyklus() {
        val startIntent = Intent(context, AudioRecordingService::class.java).apply {
            putExtra(EXTRA_START_AUDIO_MONITORING, true)
        }
        context.startForegroundService(startIntent)

        composeRule.waitUntil(timeoutMillis = 7_000L) {
            AudioRecordingService.laeuft.value &&
                notificationManager.activeNotifications.any { it.id == 1 }
        }

        assertTrue(AudioRecordingService.laeuft.value)
        val notif = notificationManager.activeNotifications.firstOrNull { it.id == 1 }
        assertNotNull(notif)
        assertTrue(notif!!.isOngoing)

        val stopIntent = Intent(context, AudioRecordingService::class.java).apply {
            action = ACTION_STOP_AUDIO_RECORDING
        }
        context.startService(stopIntent)

        composeRule.waitUntil(timeoutMillis = 7_000L) {
            !AudioRecordingService.laeuft.value &&
                notificationManager.activeNotifications.none { it.id == 1 }
        }

        assertFalse(AudioRecordingService.laeuft.value)
        assertTrue(notificationManager.activeNotifications.none { it.id == 1 })
    }
}
