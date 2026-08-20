package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
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
 * Läuft gegen die echte, produktive DiagnoseScreen()-Funktion mit vollem AppContainer, wie
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

        composeRule.setContent { DiagnoseScreen(onBack = {}) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Kein Eintrag", substring = true).assertIsDisplayed()

        runBlocking {
            container.database.diagnosticLogDao().insert(
                DiagnosticLogEntity(timestamp = 1_700_000_000_000L, message = "DEGRADED: Testeintrag")
            )
        }

        // Rooms Flow-Invalidierung laeuft auf einem eigenen Query-Executor, nicht auf dem
        // Android-Main-Looper - waitForIdle() allein drainiert diesen nicht zuverlaessig.
        // waitUntil pollt, bis die Recomposition tatsaechlich angekommen ist.
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("DEGRADED: Testeintrag", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("DEGRADED: Testeintrag", substring = true).assertIsDisplayed()
    }
}
