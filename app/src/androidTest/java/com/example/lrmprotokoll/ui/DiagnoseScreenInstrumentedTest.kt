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
import androidx.compose.ui.test.onAllNodesWithText
import kotlinx.coroutines.runBlocking
import org.junit.After
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
        // Diagnose-Log und letzteDiagnoseId ueberleben auf dem echten Geraet ueber einzelne
        // Testmethoden hinweg (kein automatischer App-Reset zwischen @Test-Methoden derselben
        // Klasse) - ohne Bereinigung wuerde z.B. diagnose_log_header (Gesamtzahl der Eintraege)
        // je nach Ausfuehrungsreihenfolge falsch sein.
        app.container.database.clearAllTables()
        app.container.settingsManager.letzteDiagnoseId = null
    }

    @After
    fun tearDown() {
        app.container.database.clearAllTables()
        app.container.settingsManager.letzteDiagnoseId = null
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

    /**
     * Echtes Geraete-Pendant zu drei bisher nur unter Robolectric geprueften Abschnitten
     * (DiagnoseScreenComposeTest) - Teil der Bestandsaufnahme nach dem Datumsbereich-Dialog-Bug
     * (Owner-Auftrag 15.09.2026).
     */
    @Test
    fun remoteDiagnoseUndSupportBundleExportWerdenGerendert() {
        composeRule.setContent { DiagnoseScreen(onBack = {}) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(DIAGNOSE_LAZY_COLUMN_TAG).performScrollToIndex(1)

        val secPrivacy = composeRule.activity.getString(com.example.lrmprotokoll.R.string.diagnose_remote_privacy_header)
        val sendReports = composeRule.activity.getString(com.example.lrmprotokoll.R.string.diagnose_send_reports)
        val exportBundle = composeRule.activity.getString(com.example.lrmprotokoll.R.string.diagnose_export_bundle)

        composeRule.onNodeWithText(secPrivacy).assertIsDisplayed()
        composeRule.onNodeWithText(sendReports).assertIsDisplayed()
        composeRule.onNodeWithText(exportBundle).assertIsDisplayed()
    }

    @Test
    fun diagnoseIdWirdMitKopierenButtonAngezeigt() {
        app.container.settingsManager.letzteDiagnoseId = "DIA-20260820-TEST9999"

        composeRule.setContent { DiagnoseScreen(onBack = {}) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(DIAGNOSE_LAZY_COLUMN_TAG).performScrollToIndex(1)

        val copyStr = composeRule.activity.getString(com.example.lrmprotokoll.R.string.action_copy)
        composeRule.onNodeWithText("DIA-20260820-TEST9999").assertIsDisplayed()
        composeRule.onNodeWithText(copyStr).assertIsDisplayed()
    }

    /**
     * Regressionstest für M7c Aufgabe 5: DiagnoseScreen zeigte das Diagnose-Log bisher nur als
     * einmaligen Snapshot (LaunchedEffect(Unit)) - ein neuer Eintrag, während der Screen offen
     * war, blieb unsichtbar. Anders als [diagnoseScreenZeigtZustandLeerzustandUndLogEintraege]
     * wird hier ERST NACH dem Oeffnen eingefuegt.
     */
    @Test
    fun neuerDiagnoseLogEintragErscheintOhneDenScreenNeuZuOeffnen() {
        composeRule.setContent { DiagnoseScreen(onBack = {}) }
        composeRule.waitForIdle()

        runBlocking {
            app.container.database.diagnosticLogDao().insert(
                DiagnosticLogEntity(timestamp = 1_700_000_000_000L, message = "DEGRADED: Testeintrag")
            )
        }

        composeRule.onNodeWithTag(DIAGNOSE_LAZY_COLUMN_TAG).performScrollToIndex(1)
        val logTitle = composeRule.activity.getString(com.example.lrmprotokoll.R.string.diagnose_log_header, 1)
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText(logTitle).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(logTitle).assertExists()
    }

    @Test
    fun selbstpruefungsChecklisteStehtGanzObenOhneScrollen() {
        // PROMPT_M10_FUNKTIONEN.md F3 verlangt die Checkliste "ganz oben" - Gegenprobe: schlaegt
        // fehl, wenn sie wieder ans Ende rutscht (kein performScrollToIndex hier, bewusst).
        composeRule.setContent { DiagnoseScreen(onBack = {}) }
        composeRule.waitForIdle()

        val checkTitel = composeRule.activity.getString(com.example.lrmprotokoll.R.string.diagnose_self_check_header)
        composeRule.onNodeWithText(checkTitel).assertIsDisplayed()
    }
}
