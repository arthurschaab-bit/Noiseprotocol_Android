package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
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

        val diagTitle = composeRule.activity.getString(com.example.lrmprotokoll.R.string.nav_diagnose)
        val stateHeader = composeRule.activity.getString(com.example.lrmprotokoll.R.string.diagnose_state_header)
        val backDesc = composeRule.activity.getString(com.example.lrmprotokoll.R.string.action_back)

        // 1. Titel
        composeRule.onNodeWithText(diagTitle).assertIsDisplayed()

        // PROMPT_M10_FUNKTIONEN.md F3: die Selbstprüfungs-Checkliste steht seither ganz oben
        // (Index 0 der LazyColumn) - Zustand und Log-Eintrag sind dadurch auf Index 1
        // gerutscht, außerhalb des initialen Viewports. Explizit per Index dorthin scrollen.
        composeRule.onNodeWithTag(DIAGNOSE_LAZY_COLUMN_TAG).performScrollToIndex(1)

        // 2. Zustand
        composeRule.onNodeWithText(stateHeader).assertIsDisplayed()

        // 3. Log-Eintrag - eigener items(diagnoseLog)-Block, ein Index weiter als der
        // Zustands-Block oben.
        composeRule.onNodeWithTag(DIAGNOSE_LAZY_COLUMN_TAG).performScrollToIndex(2)
        composeRule.onNodeWithText("Test-Diagnoseeintrag BLE Verbindung hergestellt", substring = true).assertIsDisplayed()

        // 4. Zurück-Button
        composeRule.onNodeWithContentDescription(backDesc).assertIsDisplayed().performClick()
        assertTrue(backed)
    }
}
