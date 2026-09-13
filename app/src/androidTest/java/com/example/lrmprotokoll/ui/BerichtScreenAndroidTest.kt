package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.data.AppDatabase
import com.example.lrmprotokoll.ui.theme.LaermprotokollTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentierte UI-Tests für den [BerichtScreen] - Nachfolger von
 * [ProtokollScreenAndroidTest]s ehemaligem Zeitraum-/Gesamtbericht-Dialogtest, seit der Dialog
 * mit dem Layout-Umbau (Owner-Vorgabe 12.09.2026) vom "Daten"-Tab in den eigenen "Bericht"-Tab
 * verschoben wurde.
 *
 * Verifiziert auf dem Android-Emulator (API 34 ATD):
 * 1. Navigation: Back-Button.
 * 2. Zeitraumbericht-Dialog: Öffnen, Presets, Abbrechen.
 * 3. Drei-Punkt-Menü: öffnet die Bericht-Einstellungsseite.
 */
@RunWith(AndroidJUnit4::class)
class BerichtScreenAndroidTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: LaermprotokollApp
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        db = app.container.database
        db.clearAllTables()
    }

    @After
    fun tearDown() {
        db.clearAllTables()
    }

    @Test
    fun berichtScreen_backButtonFunktioniert() {
        var backed = false

        composeRule.setContent {
            LaermprotokollTheme {
                BerichtScreen(onBack = { backed = true }, onOpenSettings = {})
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("btn_bericht_back").assertIsDisplayed().performClick()
        assertTrue("onBack muss nach Klick auf btn_bericht_back aufgerufen werden", backed)
    }

    @Test
    fun berichtScreen_zeitraumberichtDialog_oeffnetUndSchliesstPerAbbrechen() {
        composeRule.setContent {
            LaermprotokollTheme {
                BerichtScreen(onBack = {}, onOpenSettings = {})
            }
        }
        composeRule.waitForIdle()

        // Klick auf den Berichts-Button
        composeRule.onNodeWithTag("btn_period_report").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        // Dialog-Titel & Presets müssen sichtbar sein
        val dialogTitle = composeRule.activity.getString(R.string.period_report_dialog_title)
        composeRule.onNodeWithText(dialogTitle).assertIsDisplayed()

        composeRule.onNodeWithTag("btn_period_preset_7d").assertIsDisplayed()
        composeRule.onNodeWithTag("btn_period_preset_30d").assertIsDisplayed()
        composeRule.onNodeWithTag("btn_period_preset_month").assertIsDisplayed()

        // Klick auf "Abbrechen"
        composeRule.onNodeWithTag("btn_period_dialog_cancel").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        // Dialog muss geschlossen sein
        composeRule.onNodeWithTag("btn_period_dialog_cancel").assertDoesNotExist()
        composeRule.onNodeWithText(dialogTitle).assertDoesNotExist()
    }

    @Test
    fun berichtScreen_dreiPunktMenue_oeffnetEinstellungen() {
        var settingsOpened = false

        composeRule.setContent {
            LaermprotokollTheme {
                BerichtScreen(onBack = {}, onOpenSettings = { settingsOpened = true })
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("btn_bericht_menu").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("menu_item_bericht_settings").assertIsDisplayed().performClick()

        assertTrue("onOpenSettings muss aufgerufen werden", settingsOpened)
    }
}
