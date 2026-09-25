package com.example.lrmprotokoll.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.data.SessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentierter Drehungstest (F-01) für Sheets mit [MainActivity].
 *
 * Simuliert den echten Activity-Neuaufbau via `scenario.recreate()` und verifiziert,
 * dass:
 * 1. Offene BottomSheets nach der Drehung offen bleiben.
 * 2. Benutzereingaben in den Formularfeldern vollständig erhalten bleiben.
 */
@RunWith(AndroidJUnit4::class)
class SheetDrehungInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var app: LaermprotokollApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.container.settingsManager.onboardingCompleted = true
        app.container.settingsManager.stammdatenAbfrageAktiv = true
        app.container.settingsManager.fotoDokuAktiv = false
        runBlocking {
            app.container.database.clearAllTables()
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            app.container.database.clearAllTables()
        }
    }

    @Test
    fun stammdatenSheetEingabenUeberstehenDrehung() {
        // Offene Session anlegen, damit stammdatenSheetFuerSession aufgerufen wird
        runBlocking {
            app.container.database.sessionDao().insert(
                SessionEntity(
                    startedAt = System.currentTimeMillis(),
                    operatingMode = "METER_PCE",
                )
            )
        }
        composeRule.waitForIdle()

        // Warten bis Stammdaten-Sheet erscheint
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("input_bericht_geraet_hersteller").fetchSemanticsNodes().isNotEmpty()
        }

        // Felder ausfüllen
        composeRule.onNodeWithTag("input_bericht_geraet_hersteller").performTextInput("PCE Instruments")
        composeRule.onNodeWithTag("input_bericht_messort").performTextInput("Balkon Süd")
        composeRule.waitForIdle()

        // Echte Drehung (Activity recreate) ausführen
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        // Verifizieren: Sheet ist nach wie vor offen und Eingaben sind erhalten
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("input_bericht_geraet_hersteller").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("input_bericht_geraet_hersteller").assertTextContains("PCE Instruments")
        composeRule.onNodeWithTag("input_bericht_messort").assertTextContains("Balkon Süd")
    }

    @Test
    fun fotoSheetEingabenUeberstehenDrehung() {
        app.container.settingsManager.stammdatenAbfrageAktiv = false
        app.container.settingsManager.fotoDokuAktiv = true

        // Offene Session anlegen, damit fotoSheetFuerSession aufgerufen wird
        runBlocking {
            app.container.database.sessionDao().insert(
                SessionEntity(
                    startedAt = System.currentTimeMillis(),
                    operatingMode = "METER_PCE",
                )
            )
        }
        composeRule.waitForIdle()

        // Warten bis Foto-Sheet erscheint
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("input_foto_notiz").fetchSemanticsNodes().isNotEmpty()
        }

        // Notiz eingeben
        composeRule.onNodeWithTag("input_foto_notiz").performTextInput("1.5m ueber Boden")
        composeRule.waitForIdle()

        // Echte Drehung (Activity recreate) ausführen
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        // Verifizieren: Sheet ist nach wie vor offen und Notiz ist erhalten
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("input_foto_notiz").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("input_foto_notiz").assertTextContains("1.5m ueber Boden")
    }
}
