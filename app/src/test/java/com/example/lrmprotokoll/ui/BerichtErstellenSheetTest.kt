package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.data.MeasurementEntity
import com.example.lrmprotokoll.data.ReportConfigEntity
import com.example.lrmprotokoll.data.SessionEntity
import com.example.lrmprotokoll.data.StammdatenVerlaufEntity
import com.example.lrmprotokoll.diagnose.DiagnosticCode
import com.example.lrmprotokoll.diagnose.DiagnosticSeverity
import com.example.lrmprotokoll.report.BerichtZeitraum
import com.example.lrmprotokoll.report.ChaquopyReportRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean

/** Der UI-Pfad erreicht den Runner und zeigt dessen Dateifehler ohne Absturz. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BerichtErstellenSheetTest {

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    @After
    fun datenbankZuruecksetzen() {
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        runBlocking(Dispatchers.IO) {
            app.container.database.clearAllTables()
        }
    }

    /**
     * Öffnet das Sheet über "Neuer Bericht" und wartet, bis der Startknopf freigegeben ist.
     *
     * Solange `laedt == true` ist, zeigt `BerichtErstellenSheet` einen `CircularProgressIndicator`
     * - eine unbestimmte, also endlose Animation. Mit `autoAdvance = true` fordert sie fortlaufend
     * neue Frames an, die Leerlauferkennung wird nie fertig und `waitUntil` läuft in die
     * `ComposeTimeoutException`, obwohl der Ladevorgang längst abgeschlossen wäre.
     *
     * Deshalb wird die Testuhr angehalten und pro Prüfschritt gezielt weitergestellt; das leert den
     * Main-Looper, ohne die Animation mitlaufen zu lassen. `autoAdvance` wird im `finally` wieder
     * eingeschaltet, damit nachfolgende Schritte unverändert arbeiten. Dasselbe Muster löst in
     * `ReportConfigSettingsTest` das `ExposedDropdownMenu` (c7c16f2); die Klasse von Fehlern ist in
     * docs/CI_FLAKINESS_UNTERSUCHUNG_BERICHT.md Abschnitt 4.5 beschrieben.
     */
    private fun oeffneSheetUndWarteAufStartknopf() {
        try {
            composeRule.mainClock.autoAdvance = false
            composeRule.onNodeWithTag("btn_bericht_erstellen_v2").performClick()
            composeRule.mainClock.advanceTimeBy(500)
            composeRule.waitUntil(timeoutMillis = 15_000L) {
                composeRule.mainClock.advanceTimeBy(50)
                runCatching {
                    composeRule.onNodeWithTag("btn_bericht_erstellen_start").assertIsEnabled()
                }.isSuccess
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test fun neuerBerichtButtonOeffnetAblaufUndDateifehlerIstVerstaendlich() {
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        val jetzt = System.currentTimeMillis()
        val datum = LocalDate.now()
        runBlocking {
            val db = app.container.database
            val sessionId = db.sessionDao().insert(
                SessionEntity(startedAt = jetzt - 1_000, endedAt = jetzt + 1_000,
                    deviceAddress = "AA:BB", deviceName = "PCE-323", weighting = "A",
                    timeWeighting = "FAST")
            )
            db.measurementDao().insertAll(listOf(
                MeasurementEntity(sessionId = sessionId, timestamp = jetzt, levelDb = 55.0,
                    weighting = "A", timeWeighting = "FAST", flags = 0)
            ))
            db.stammdatenVerlaufDao().insert(StammdatenVerlaufEntity(
                erstelltAm = jetzt, geraetHersteller = "PCE", geraetTyp = "323",
                geraetGenauigkeitsklasse = "2", geraetSeriennummer = "SN1",
                geraetKalibrierung = "kalibriert", messort = "Musterort",
                mikrofonposition = "Fenster", mikrofonhoehe = "1m",
                entfernungZurQuelle = "5m", innenAussen = "Außen",
                fensterzustand = "", wetter = "trocken", datenqualitaetHinweis = "",
            ))
            db.reportConfigDao().speichere(ReportConfigEntity(gebietseinstufung = "WA"))
        }

        val runnerAufgerufen = AtomicBoolean(false)
        composeRule.setContent {
            BerichtScreen(
                onBack = {}, onOpenSettings = {}, initialHighEndRange = BerichtZeitraum(datum, datum),
                highEndRunner = {
                    runnerAufgerufen.set(true)
                    ChaquopyReportRunner.Ergebnis.Fehler("Die Rohdaten-Datei fehlt. Bitte erneut exportieren.")
                },
            )
        }
        oeffneSheetUndWarteAufStartknopf()
        composeRule.onNodeWithTag("btn_bericht_erstellen_start").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            composeRule.onAllNodesWithTag("bericht_erstellen_fehler")
                .fetchSemanticsNodes().isNotEmpty()
        }

        assertTrue(runnerAufgerufen.get())
        composeRule.onNodeWithTag("bericht_erstellen_fehler")
            .assertTextEquals("Die Rohdaten-Datei fehlt. Bitte erneut exportieren.")
    }

    /**
     * Test 3 (PROMPT_FIX_BERICHT_HIGHEND.md Abschnitt 3): muss ohne die Änderung rot sein. Eine
     * abgelehnte Vorprüfung ist eine Nutzerangabe, kein Fehler - deshalb nur ein Breadcrumb, kein
     * REPORT_CREATE_FAILED (das war bisher nirgends verwendet, siehe docs/BEFUNDE_P30_2026-09-23.md
     * Abschnitt 3).
     */
    @Test fun abgelehnteVorpruefungHinterlaesstBreadcrumbOhneReportEvent() {
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        val diagnosticsReporter = app.container.diagnosticsReporter

        // Bewusst OHNE initialHighEndRange: erzeugen() lehnt bereits den fehlenden Datumsbereich ab,
        // bevor HighEndReportExport.generate() je aufgerufen wird.
        composeRule.setContent {
            BerichtScreen(onBack = {}, onOpenSettings = {})
        }
        oeffneSheetUndWarteAufStartknopf()
        composeRule.onNodeWithTag("btn_bericht_erstellen_start").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            composeRule
                .onAllNodesWithTag("bericht_erstellen_fehler")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        composeRule
            .onNodeWithTag("bericht_erstellen_fehler")
            .assertTextEquals("Bitte zuerst einen Datumsbereich wählen.")
        val nichtGestartet =
            diagnosticsReporter.recentBreadcrumbs().filter {
                it.category == "Bericht" &&
                    it.message == "High-End-Bericht nicht gestartet: Bitte zuerst einen Datumsbereich wählen."
            }
        assertEquals("Genau ein Breadcrumb für die abgelehnte Vorprüfung", 1, nichtGestartet.size)
        assertEquals(DiagnosticSeverity.INFO, nichtGestartet.single().level)
        val reportEvents = diagnosticsReporter.recentEvents().filter { it.code == DiagnosticCode.REPORT_CREATE_FAILED }
        assertTrue("Eine abgelehnte Vorprüfung darf kein Report-Event erzeugen", reportEvents.isEmpty())
    }
}
