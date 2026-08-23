package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
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

        val copyStr = composeRule.activity.getString(com.example.lrmprotokoll.R.string.action_copy)
        composeRule.onNodeWithText("DIA-20260820-TEST9999").assertIsDisplayed()
        composeRule.onNodeWithText(copyStr).assertIsDisplayed()
    }
}
