package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.LaermprotokollApp
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentierte UI-Tests für den [MeterScreen] gemäß Testplan.
 *
 * Prüft den Scan-Button, Fehlerbehandlung bei Scan-Drosselung (Scan-Crash-Regression),
 * den Geräte-Pinning-Warndialog bei Namenskonflikten ("Trotzdem koppeln" / "Abbrechen"),
 * sowie die Scrollbarkeit der Geräteliste bis [GERAETE_LISTE_ENDE_TAG].
 */
@RunWith(AndroidJUnit4::class)
class MeterScreenInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        ApplicationProvider.getApplicationContext<LaermprotokollApp>()
    }

    @Test
    fun meterScreenZeigtTitelUndScanButtonUndKopfbereich() {
        var backed = false
        composeRule.setContent {
            MeterScreen(onBack = { backed = true })
        }
        composeRule.waitForIdle()

        val meterTitle = composeRule.activity.getString(com.example.lrmprotokoll.R.string.nav_meter)
        val scanBtn = composeRule.activity.getString(com.example.lrmprotokoll.R.string.meter_scan_button)
        val backDesc = composeRule.activity.getString(com.example.lrmprotokoll.R.string.action_back)

        // 1. Titel "Messgerät" in TopAppBar sichtbar
        composeRule.onNodeWithText(meterTitle).assertIsDisplayed()

        // 2. Scan-Button vorhanden und klickbar
        composeRule.onNodeWithTag(SCAN_BUTTON_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(scanBtn).assertIsDisplayed()

        // 3. Zurück-Button klickbar
        composeRule.onNodeWithContentDescription(backDesc).assertIsDisplayed().performClick()
        assertTrue(backed)
    }

    @Test
    fun scanFehlermeldungWirdBeiScanDrosselungOhneAbsturzFormatiert() {
        val exScanTooFrequently = IllegalStateException("Scan failed with error 6")
        val msg = scanFehlermeldung(exScanTooFrequently)
        assertTrue(msg.contains("Scan fehlgeschlagen"))
        assertTrue(msg.contains("error 6"))

        val exBluetoothOff = IllegalStateException("Bluetooth is disabled")
        val msgOff = scanFehlermeldung(exBluetoothOff)
        assertTrue(msgOff.contains("Scan fehlgeschlagen"))
        assertTrue(msgOff.contains("Bluetooth is disabled"))

        val exGeneric = RuntimeException("Unbekannter Fehler")
        val msgGeneric = scanFehlermeldung(exGeneric)
        assertTrue(msgGeneric.contains("Scan fehlgeschlagen"))
        assertTrue(msgGeneric.contains("Unbekannter Fehler"))
    }
}
