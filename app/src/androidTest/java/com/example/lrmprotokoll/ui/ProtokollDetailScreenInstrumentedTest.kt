package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.data.MeasurementEntity
import com.example.lrmprotokoll.data.SessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentierte UI-Tests für den [ProtokollDetailScreen] gemäß Testplan.
 *
 * Prüft das Laden einer Session, die Anzeige der Kennwerte und Pegelverlauf-Sektionen,
 * die Export-Buttons ("CSV exportieren", "PDF exportieren") und den Fehlerzustand bei
 * nicht vorhandener Session-ID.
 */
@RunWith(AndroidJUnit4::class)
class ProtokollDetailScreenInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: LaermprotokollApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
    }

    /**
     * Wartet, bis der Bildschirm fertig geladen hat.
     *
     * [androidx.compose.ui.test.junit4.ComposeTestRule.waitForIdle] reicht dafuer NICHT:
     * [ProtokollDetailScreen] laedt die Session in einem `LaunchedEffect` ueber Room, und bis
     * dahin zeigt er einen Ladeindikator. waitForIdle wartet auf die Ruhe der Compose-Pipeline,
     * nicht auf eine Datenbankabfrage auf einem anderen Dispatcher - der Test hat also je nach
     * Laufzeit der Abfrage mal den Ladeindikator und mal den fertigen Inhalt gesehen. Genau das
     * hat ihn auf dem CI-Emulator sporadisch scheitern lassen.
     */
    private fun wartetBisAngezeigt(text: String) {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun protokollDetailScreenZeigtKennwerteUndExportButtonsFuerGueltigeSession() {
        val db = app.container.database
        var sessionId: Long = 0L

        runBlocking {
            sessionId = db.sessionDao().insert(
                SessionEntity(
                    startedAt = 1716000000000L,
                    endedAt = 1716003600000L,
                    deviceAddress = "AA:BB:CC:DD:EE:FF",
                    deviceName = "PCE-323 Testgerät",
                    weighting = "A",
                    timeWeighting = "F",
                    range = "30-130"
                )
            )

            // Einige Messwerte hinzufügen
            db.measurementDao().insertAll(
                listOf(
                    MeasurementEntity(sessionId = sessionId, timestamp = 1716000001000L, levelDb = 54.2, weighting = "A", flags = 0),
                    MeasurementEntity(sessionId = sessionId, timestamp = 1716000002000L, levelDb = 61.8, weighting = "A", flags = 0),
                    MeasurementEntity(sessionId = sessionId, timestamp = 1716000003000L, levelDb = 72.5, weighting = "A", flags = 0),
                    MeasurementEntity(sessionId = sessionId, timestamp = 1716000004000L, levelDb = 58.0, weighting = "A", flags = 0)
                )
            )
        }

        var backed = false

        composeRule.setContent {
            ProtokollDetailScreen(sessionId = sessionId, onBack = { backed = true })
        }
        val sessionTitle = composeRule.activity.getString(com.example.lrmprotokoll.R.string.protocol_tab_sessions)
        val metricsTitle = composeRule.activity.getString(com.example.lrmprotokoll.R.string.protocol_detail_metrics)
        val historyTitle = composeRule.activity.getString(com.example.lrmprotokoll.R.string.cockpit_history_title)
        val csvBtn = composeRule.activity.getString(com.example.lrmprotokoll.R.string.action_export_csv)
        val pdfBtn = composeRule.activity.getString(com.example.lrmprotokoll.R.string.action_export_pdf)
        val backDesc = composeRule.activity.getString(com.example.lrmprotokoll.R.string.action_back)

        // 1. Titel & Kopfzeile - erst warten, bis die Session aus Room geladen ist.
        wartetBisAngezeigt(sessionTitle)
        composeRule.onNodeWithText(sessionTitle, substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("PCE-323 Testgerät", substring = true).assertIsDisplayed()

        // 2. Kennwerte- und Pegelverlauf-Überschriften
        composeRule.onNodeWithText(metricsTitle).assertIsDisplayed()
        composeRule.onNodeWithText(historyTitle).assertIsDisplayed()

        // 3. Export-Buttons vorhanden
        composeRule.onNodeWithText(csvBtn).assertIsDisplayed()
        composeRule.onNodeWithText(pdfBtn).assertIsDisplayed()

        // 4. Zurück-Navigation
        composeRule.onNodeWithContentDescription(backDesc).assertIsDisplayed().performClick()
        assertTrue(backed)
    }

    @Test
    fun protokollDetailScreenZeigtFehlermeldungBeiUngueltigerSessionId() {
        var backed = false

        composeRule.setContent {
            ProtokollDetailScreen(sessionId = 999999999L, onBack = { backed = true })
        }
        val notFoundText = composeRule.activity.getString(com.example.lrmprotokoll.R.string.protocol_session_not_found)
        val backDesc = composeRule.activity.getString(com.example.lrmprotokoll.R.string.action_back)

        // Auch der Fehlerzustand erscheint erst, wenn die Abfrage durch ist - bis dahin laeuft
        // der Ladeindikator, und die Pruefung wuerde ihn statt der Meldung sehen.
        wartetBisAngezeigt(notFoundText)
        composeRule.onNodeWithText(notFoundText).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(backDesc).assertIsDisplayed().performClick()
        assertTrue(backed)
    }
}
