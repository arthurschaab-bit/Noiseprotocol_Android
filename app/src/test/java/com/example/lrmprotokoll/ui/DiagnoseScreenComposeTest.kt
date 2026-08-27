package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.data.DiagnosticLogEntity
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regressionstest für M7c Aufgabe 5: DiagnoseScreen zeigte das Diagnose-Log bisher nur als
 * einmaligen Snapshot (LaunchedEffect(Unit)) - ein neuer Eintrag, während der Screen offen war,
 * blieb unsichtbar, bis man ihn schloss und neu öffnete. Jetzt beobachtet der Screen
 * DiagnosticLogDao.alle() als Flow direkt (collectAsState), analog zu NoiseDao.getAll().
 *
 * Läuft gegen die echte, productive DiagnoseScreen()-Funktion mit vollem AppContainer, wie
 * MeterScreenComposeTest/SettingsScreenComposeTest.
 *
 * PROMPT_M10_FUNKTIONEN.md F3: die Selbstprüfungs-Checkliste steht seither ganz oben (Index 0
 * der LazyColumn), der hier geprüfte Inhalt ist dadurch auf Index 1 gerutscht - außerhalb des
 * initialen Viewports und außerhalb der Lazy-Prefetch-Reichweite. Deshalb hier erst explizit per
 * Index dorthin scrollen (performScrollToIndex über den DIAGNOSE_LAZY_COLUMN_TAG), statt sich auf
 * performScrollTo() eines noch gar nicht komponierten Knotens zu verlassen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiagnoseScreenComposeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun neuerDiagnoseLogEintragErscheintOhneDenScreenNeuZuOeffnen() {
        val container = ApplicationProvider.getApplicationContext<LaermprotokollApp>().container

        runBlocking {
            container.database.diagnosticLogDao().insert(
                DiagnosticLogEntity(timestamp = 1_700_000_000_000L, message = "DEGRADED: Testeintrag")
            )
        }

        composeRule.setContent { DiagnoseScreen(onBack = {}) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(DIAGNOSE_LAZY_COLUMN_TAG).performScrollToIndex(1)

        val logTitle = composeRule.activity.getString(com.example.lrmprotokoll.R.string.diagnose_log_header, 1)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(logTitle).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(logTitle).assertExists()
    }

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
        val container = ApplicationProvider.getApplicationContext<LaermprotokollApp>().container
        container.settingsManager.letzteDiagnoseId = "DIA-20260820-TEST9999"

        composeRule.setContent { DiagnoseScreen(onBack = {}) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(DIAGNOSE_LAZY_COLUMN_TAG).performScrollToIndex(1)

        val copyStr = composeRule.activity.getString(com.example.lrmprotokoll.R.string.action_copy)
        composeRule.onNodeWithText("DIA-20260820-TEST9999").assertIsDisplayed()
        composeRule.onNodeWithText(copyStr).assertIsDisplayed()
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
