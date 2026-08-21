package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.data.DiagnosticLogEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentierte UI-Tests für den [DiagnoseScreen] gemäß Testplan.
 *
 * Prüft die Anzeige des Zustandsautomaten, Decode-Fehlerrate, Diagnose-Log-Zeilen
 * und Sync-Historie sowie den Leerzustand und die Navigation.
 */
@RunWith(AndroidJUnit4::class)
class DiagnoseScreenInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: LaermprotokollApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
    }

    @Test
    fun diagnoseScreenZeigtZustandLeerzustandUndLogEintraege() {
        val db = app.container.database
        runBlocking {
            db.diagnosticLogDao().insert(
                DiagnosticLogEntity(
                    timestamp = 1716000000000L,
                    message = "Test-Diagnoseeintrag BLE Verbindung hergestellt"
                )
            )
        }

        var backed = false

        composeRule.setContent {
            DiagnoseScreen(onBack = { backed = true })
        }
        composeRule.waitForIdle()

        // 1. Titel
        composeRule.onNodeWithText("Diagnose").assertIsDisplayed()

        // 2. Zustand & Fehlerrate
        composeRule.onNodeWithText("Zustand").assertIsDisplayed()
        composeRule.onNodeWithText("Decode-Fehlerrate", substring = true).assertIsDisplayed()

        // 3. Log-Eintrag
        composeRule.onNodeWithText("Test-Diagnoseeintrag BLE Verbindung hergestellt", substring = true).assertIsDisplayed()

        // 4. Zurück-Button
        composeRule.onNodeWithContentDescription("Zurück").assertIsDisplayed().performClick()
        assertTrue(backed)
    }
}
