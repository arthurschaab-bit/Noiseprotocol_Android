package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.data.AppDatabase
import com.example.lrmprotokoll.ui.theme.LaermprotokollTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regressionstest fuer einen auf echtem Geraet gefundenen Bug (Owner-Meldung 15.09.2026):
 * [BerichtErstellenSheetTest] (Robolectric, app/src/test) prueft den Datumsbereich-Dialog nie
 * wirklich - der Zeitraum wird dort per `initialHighEndRange` direkt injiziert, ohne den Dialog
 * je zu oeffnen. Dadurch blieb unbemerkt, dass `DateRangePicker` in einem `DatePickerDialog` ohne
 * Hoehenbegrenzung auf einem echten Bildschirm ueberlief: Uebernehmen/Abbrechen existierten zwar
 * im Semantics-Baum, lagen aber ausserhalb des sichtbaren Bereichs. `assertIsDisplayed()` prueft
 * echte Bounds/Sichtbarkeit (nicht nur Praesenz) und haette den Bug gefangen.
 */
@RunWith(AndroidJUnit4::class)
class BerichtErstellenSheetInstrumentedTest {

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
    fun datumsbereichDialog_uebernehmenUndAbbrechenSindWirklichSichtbar() {
        composeRule.setContent {
            LaermprotokollTheme {
                BerichtScreen(onBack = {}, onOpenSettings = {})
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("btn_bericht_erstellen_v2").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("btn_bericht_datumsbereich").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        // DIAGNOSE-VARIANTE (nicht mergen): testTags gibt es im alten, kaputten Code noch nicht -
        // Text-Lookup, wie er im alten DatePickerDialog tatsaechlich vorhanden war.
        composeRule.onNodeWithText("Übernehmen").assertIsDisplayed()
        composeRule.onNodeWithText("Abbrechen").assertIsDisplayed()
    }
}
