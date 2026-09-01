package com.example.lrmprotokoll.ui

import android.Manifest
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlarmManager

/**
 * Testluecken-Auftrag Stufe 6: ersetzt den bisherigen reinen Render-Smoke-Test durch echte
 * Interaktionsketten - jeder der "Empfohlene Aktionen"-Buttons muss beim Klick genau den
 * richtigen System-Einstellungsdialog starten (sonst tippt der Nutzer ins Leere), und der
 * "Optimal konfiguriert"-Zustand darf keine Aktions-Buttons zeigen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OemDeviceHelperCardTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var context: LaermprotokollApp

    private fun konfiguriereAlsOptimal() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        shadowOf(powerManager).setIgnoringBatteryOptimizations(context.packageName, true)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @Test
    fun oemDeviceHelperCardWirdErfolgreichGerendert() {
        ApplicationProvider.getApplicationContext<LaermprotokollApp>()

        composeRule.setContent {
            OemDeviceHelperCard()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(OEM_HELPER_CARD_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Geräte- & Alarm-Diagnose").assertIsDisplayed()
    }

    @Test
    fun optimalerZustandZeigtKeineAktionsButtonsUndDenOptimalBadge() {
        konfiguriereAlsOptimal()

        composeRule.setContent { OemDeviceHelperCard() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Optimal konfiguriert").assertIsDisplayed()
        composeRule.onNodeWithText("Akku-Optimierung aufheben").assertIsNotDisplayed()
        composeRule.onNodeWithText("Benachrichtigungen erlauben").assertIsNotDisplayed()
    }

    @Test
    fun klickAufAkkuOptimierungAufhebenButtonStartetDenRichtigenSystemDialog() {
        konfiguriereAlsOptimal()
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        // Einziges verbleibendes Problem: Akku-Optimierung aktiv - so ist der Button eindeutig.
        shadowOf(powerManager).setIgnoringBatteryOptimizations(context.packageName, false)

        composeRule.setContent { OemDeviceHelperCard() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Prüfung nötig").assertIsDisplayed()
        composeRule.onNodeWithText("Akku-Optimierung aufheben").assertIsDisplayed().performClick()

        val gestarteteIntent = shadowOf(composeRule.activity).nextStartedActivity
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, gestarteteIntent.action)
        assertEquals("package:${context.packageName}", gestarteteIntent.data.toString())
    }

    @Test
    fun klickAufBenachrichtigungenErlaubenButtonStartetDenRichtigenSystemDialog() {
        konfiguriereAlsOptimal()
        // Einziges verbleibendes Problem: Benachrichtigungsberechtigung fehlt.
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        composeRule.setContent { OemDeviceHelperCard() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Benachrichtigungen erlauben").assertIsDisplayed().performClick()

        val gestarteteIntent = shadowOf(composeRule.activity).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, gestarteteIntent.action)
        assertEquals(context.packageName, gestarteteIntent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun klickAufExakteAlarmeFreischaltenButtonStartetDenRichtigenSystemDialog() {
        konfiguriereAlsOptimal()
        // Einziges verbleibendes Problem: exakte Alarme eingeschraenkt (nur ab Android 12/S relevant).
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        composeRule.setContent { OemDeviceHelperCard() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Exakte Alarme freischalten").assertIsDisplayed().performClick()

        val gestarteteIntent = shadowOf(composeRule.activity).nextStartedActivity
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, gestarteteIntent.action)
        assertEquals("package:${context.packageName}", gestarteteIntent.data.toString())
    }
}
