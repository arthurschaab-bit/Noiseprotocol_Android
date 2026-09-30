package com.example.lrmprotokoll.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.data.AppDatabase
import com.example.lrmprotokoll.data.SessionEntity
import com.example.lrmprotokoll.ui.theme.LaermprotokollTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/**
 * Instrumentierte UI-Tests für den [BerichtScreen] - Nachfolger von
 * [ProtokollScreenAndroidTest]s ehemaligem Zeitraum-/Gesamtbericht-Dialogtest, seit der Dialog
 * mit dem Layout-Umbau (Owner-Vorgabe 12.09.2026) vom "Daten"-Tab in den eigenen "Bericht"-Tab
 * verschoben wurde.
 *
 * Verifiziert auf dem Android-Emulator (API 34 ATD):
 * 1. Navigation: Back-Button.
 * 2. Zeitraum, Schnellwahl und die drei benannten Ausgaben (seit S-4); Vollbild-Datumswähler.
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
        val editor = app.getSharedPreferences("noise_settings", Context.MODE_PRIVATE).edit()
        editor.remove("high_end_bericht_erster_tag")
        editor.remove("high_end_bericht_letzter_tag")
        editor.commit()
    }

    @Test
    fun highEndBerichtStartetMitLetzterBeendeterMessung() {
        val messtag = LocalDate.of(2026, 9, 25)
        val zone = ZoneId.systemDefault()
        val beginn =
            messtag
                .atTime(10, 0)
                .atZone(zone)
                .toInstant()
                .toEpochMilli()
        val ende =
            messtag
                .atTime(11, 0)
                .atZone(zone)
                .toInstant()
                .toEpochMilli()
        runBlocking {
            db.sessionDao().insert(
                SessionEntity(
                    startedAt = beginn,
                    endedAt = ende,
                    deviceAddress = "Test",
                    deviceName = "Testgerät",
                    weighting = null,
                    timeWeighting = null,
                ),
            )
        }

        composeRule.setContent {
            LaermprotokollTheme {
                BerichtScreen(onBack = {}, onOpenSettings = {})
            }
        }
        composeRule.onNodeWithTag("btn_bericht_erstellen_v2").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            composeRule.onAllNodesWithTag("bericht_datumsbereich_anzeige").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("bericht_datumsbereich_anzeige").assertTextEquals("25.09.2026 – 25.09.2026")
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

    /**
     * Seit S-4 gibt es den Zeitraum-Dialog mit Presets und verstecktem Gesamtbericht-Schalter
     * nicht mehr. An seine Stelle treten der Zeitraum oben, die Schnellwahl-Chips und drei
     * BENANNTE Ausgaben. Der Test prueft dieselbe Absicht wie vorher - die Einstiege sind da und
     * heissen, was sie tun - nur an der neuen Oberflaeche.
     */
    @Test
    fun berichtScreen_zeitraumUndDreiBenannteAusgabenSindErreichbar() {
        composeRule.setContent {
            LaermprotokollTheme {
                BerichtScreen(onBack = {}, onOpenSettings = {})
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("bericht_zeitraum_text").assertIsDisplayed()
        composeRule.onNodeWithTag("btn_bericht_zeitraum_aendern").assertIsDisplayed()
        composeRule.onNodeWithTag("btn_bericht_preset_7").assertIsDisplayed()
        composeRule.onNodeWithTag("btn_bericht_preset_30").assertIsDisplayed()
        composeRule.onNodeWithTag("btn_bericht_preset_monat").assertIsDisplayed()

        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.bericht_ausgabe_uebersicht_titel))
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.bericht_ausgabe_gesamt_titel))
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.bericht_ausgabe_highend_titel))
            .assertIsDisplayed()
    }

    /**
     * Der Vollbild-Datumswaehler - die Bauart, die der Owner am 15.09.2026 am Geraet als einzig
     * bedienbare bestaetigt hat. Nur am Emulator pruefbar: ein Dialog liegt in einem eigenen
     * Fenster und taucht in Robolectrics decorView nicht auf.
     */
    @Test
    fun berichtScreen_datumswaehlerOeffnetUndSchliesstPerAbbrechen() {
        composeRule.setContent {
            LaermprotokollTheme {
                BerichtScreen(onBack = {}, onOpenSettings = {})
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("btn_bericht_zeitraum_aendern").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            composeRule.onAllNodesWithTag("btn_bericht_zeitraum_uebernehmen").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("btn_bericht_zeitraum_uebernehmen").assertIsDisplayed()
        composeRule.onNodeWithTag("btn_bericht_zeitraum_abbrechen").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("btn_bericht_zeitraum_uebernehmen").assertDoesNotExist()
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
