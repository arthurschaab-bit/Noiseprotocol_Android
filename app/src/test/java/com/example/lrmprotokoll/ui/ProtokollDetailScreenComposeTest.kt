package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.data.MeasurementEntity
import com.example.lrmprotokoll.data.SessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regressionstest für M7c Aufgabe 2 (Bestandsaufnahme-Vorschlag "ein Graf der zeigt seit wann
 * läuft die Aufzeichnung, welche Werte wurden gemessen"): der Pegelverlauf-Chart in
 * [ProtokollDetailScreen] muss sowohl den "keine Messwerte"-Fall anzeigen als auch bei
 * vorhandenen Rohwerten den umgebenden Bereich ("Pegelverlauf") rendern, ohne abzustürzen -
 * läuft gegen die echte, produktive ProtokollDetailScreen()-Funktion mit vollem AppContainer.
 *
 * Das Laden geschieht in einem `LaunchedEffect` per suspend-DAO-Aufrufen (Room) - waitForIdle()
 * allein drainiert diese nicht zuverlässig (dasselbe Timing-Problem wie bei
 * DiagnoseScreenComposeTest, dort mit Room-Flow-Invalidierung statt hier mit dem initialen
 * Laden). waitUntil pollt, bis "geladen" tatsächlich true geworden ist.
 *
 * timeoutMillis = 30_000: ursprünglich 5_000, dann 15_000 - auf einem voll ausgelasteten
 * CI-Runner (voller ./gradlew test-Lauf, ~300 Tests, mehrere Robolectric-JVM-Forks auf
 * begrenzten CPU-Kernen) reichte selbst 15 s stellenweise nicht aus und der Test schlug mit
 * ComposeTimeoutException fehl, obwohl er lokal (isoliert und im vollen Lauf) zuverlässig grün
 * lief - kein Logikfehler, sondern Ressourcenkonkurrenz auf dem Runner. waitUntil kehrt sofort
 * zurück, sobald die Bedingung erfüllt ist - ein großzügigeres Budget verlangsamt den
 * Erfolgsfall nicht, nur die maximale Wartezeit vor einem echten Fehlschlag steigt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProtokollDetailScreenComposeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun ohneMesswerteZeigtDerChartEinenHinweisStattZuAbstuerzen() {
        val container = ApplicationProvider.getApplicationContext<LaermprotokollApp>().container
        val sessionId = runBlocking {
            container.database.sessionDao().insert(
                SessionEntity(
                    startedAt = 1_700_000_000_000L, endedAt = 1_700_000_010_000L,
                    deviceAddress = "AA:BB", deviceName = "PCE-323", weighting = "A", timeWeighting = "SLOW",
                )
            )
        }

        val chartTitle = composeRule.activity.getString(com.example.lrmprotokoll.R.string.cockpit_history_title)
        val noChartData = composeRule.activity.getString(com.example.lrmprotokoll.R.string.protocol_detail_no_chart_data)

        composeRule.setContent { ProtokollDetailScreen(sessionId = sessionId, onBack = {}) }
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText(chartTitle).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText(chartTitle).assertIsDisplayed()
        composeRule.onNodeWithText(noChartData).assertIsDisplayed()
    }

    @Test
    fun mitMesswertenWirdDerChartOhneHinweistextGerendert() {
        val container = ApplicationProvider.getApplicationContext<LaermprotokollApp>().container
        val sessionId = runBlocking {
            val id = container.database.sessionDao().insert(
                SessionEntity(
                    startedAt = 1_700_000_000_000L, endedAt = 1_700_000_010_000L,
                    deviceAddress = "AA:BB", deviceName = "PCE-323", weighting = "A", timeWeighting = "SLOW",
                )
            )
            container.database.measurementDao().insertAll(
                listOf(
                    MeasurementEntity(sessionId = id, timestamp = 1_700_000_000_500L, levelDb = 45.0, weighting = "A", flags = 0),
                    MeasurementEntity(sessionId = id, timestamp = 1_700_000_005_000L, levelDb = 55.0, weighting = "A", flags = 0),
                )
            )
            id
        }

        val chartTitle = composeRule.activity.getString(com.example.lrmprotokoll.R.string.cockpit_history_title)
        val noChartData = composeRule.activity.getString(com.example.lrmprotokoll.R.string.protocol_detail_no_chart_data)

        composeRule.setContent { ProtokollDetailScreen(sessionId = sessionId, onBack = {}) }
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText(chartTitle).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText(chartTitle).assertIsDisplayed()
        composeRule.onNodeWithText(noChartData).assertDoesNotExist()
    }
}
