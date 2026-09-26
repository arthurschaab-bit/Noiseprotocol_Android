package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.data.DokumentationsFotoEntity
import com.example.lrmprotokoll.data.MeasurementEntity
import com.example.lrmprotokoll.data.MinuteAggregateEntity
import com.example.lrmprotokoll.data.NoiseRecord
import com.example.lrmprotokoll.data.SessionEntity
import com.example.lrmprotokoll.diagnose.DiagnosticCode
import com.example.lrmprotokoll.messreihe.AkustischeKennwerte
import com.example.lrmprotokoll.messreihe.Ausfallband
import com.example.lrmprotokoll.report.Gesamtbericht
import com.example.lrmprotokoll.report.GesamtberichtExport
import com.example.lrmprotokoll.report.GesamtberichtStammdaten
import com.example.lrmprotokoll.report.MessreiheExport
import com.example.lrmprotokoll.report.PeriodenBericht
import com.example.lrmprotokoll.report.PeriodenBerichtExport
import com.example.lrmprotokoll.report.ReportManager
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
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/**
 * Tests für F-27 (docs/PROMPT_UX_PHASE1.md):
 * Fehlerbehandlung für alle drei Exportwege (BerichtScreen Zeitraum/Gesamtbericht,
 * ProtokollDetailScreen PDF/CSV, MainActivity Tagesbericht ZIP/Text).
 *
 * Verifiziert:
 * - Dialog schließt im Fehlerfall
 * - Diagnoseereignis (REPORT_CREATE_FAILED bzw. EXPORT_FAILED) wird erfasst
 * - Nutzer erhält Fehlerrückmeldung über Snackbar
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExportFehlerbehandlungComposeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    @After
    fun datenbankZuruecksetzen() {
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        runBlocking(Dispatchers.IO) {
            app.container.database.clearAllTables()
        }
    }

    // --- Exportweg 1: BerichtScreen (Zeitraumbericht / Gesamtbericht) ---

    @Test
    fun periodenBerichtFehlerSchliesstDialogMeldetDiagnoseUndZeigtSnackbar() {
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        val diagnosticsReporter = app.container.diagnosticsReporter
        val snackbarMeldung = AtomicReference<String?>(null)

        val throwingExport =
            object : PeriodenBerichtExport(app) {
                override fun exportierePdf(
                    bericht: PeriodenBericht,
                    titel: String,
                ): File = throw IOException("Platte voll")
            }

        composeRule.setContent {
            BerichtScreen(
                onBack = {},
                onOpenSettings = {},
                onShowSnackbar = { snackbarMeldung.set(it) },
                periodenBerichtExport = throwingExport,
            )
        }

        composeRule.onNodeWithTag("btn_period_report").performClick()
        composeRule.onNodeWithTag("btn_period_preset_7d").performClick()

        composeRule.waitUntil(timeoutMillis = 15_000L) {
            snackbarMeldung.get() != null
        }

        composeRule.onNodeWithTag("btn_period_preset_7d").assertDoesNotExist()
        assertEquals(
            composeRule.activity.getString(R.string.export_failed_message),
            snackbarMeldung.get(),
        )

        val fehlerEvents = diagnosticsReporter.recentEvents().filter { it.code == DiagnosticCode.REPORT_CREATE_FAILED }
        assertTrue("Fehler-Event REPORT_CREATE_FAILED erfasst", fehlerEvents.isNotEmpty())
    }

    @Test
    fun gesamtberichtFehlerSchliesstDialogMeldetDiagnoseUndZeigtSnackbar() {
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        val diagnosticsReporter = app.container.diagnosticsReporter
        val snackbarMeldung = AtomicReference<String?>(null)

        val throwingExport =
            object : GesamtberichtExport(app) {
                override fun exportierePdf(
                    bericht: Gesamtbericht,
                    stammdaten: GesamtberichtStammdaten,
                    titel: String,
                ): File = throw IOException("Schreibfehler")
            }

        composeRule.setContent {
            BerichtScreen(
                onBack = {},
                onOpenSettings = {},
                onShowSnackbar = { snackbarMeldung.set(it) },
                gesamtberichtExportInstance = throwingExport,
            )
        }

        composeRule.onNodeWithTag("btn_period_report").performClick()
        composeRule.onNodeWithTag("switch_gesamtbericht").performClick()
        composeRule.onNodeWithTag("btn_period_preset_7d").performClick()

        composeRule.waitUntil(timeoutMillis = 15_000L) {
            snackbarMeldung.get() != null
        }

        composeRule.onNodeWithTag("btn_period_preset_7d").assertDoesNotExist()
        assertEquals(
            composeRule.activity.getString(R.string.export_failed_message),
            snackbarMeldung.get(),
        )

        val fehlerEvents = diagnosticsReporter.recentEvents().filter { it.code == DiagnosticCode.REPORT_CREATE_FAILED }
        assertTrue("Fehler-Event REPORT_CREATE_FAILED erfasst", fehlerEvents.isNotEmpty())
    }

    // --- Exportweg 2: ProtokollDetailScreen (PDF / CSV) ---

    @Test
    fun protokollDetailPdfExportFehlerMeldetDiagnoseUndZeigtSnackbar() {
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        val diagnosticsReporter = app.container.diagnosticsReporter
        val snackbarMeldung = AtomicReference<String?>(null)

        val sessionId =
            runBlocking {
                val id =
                    app.container.database.sessionDao().insert(
                        SessionEntity(
                            startedAt = 1_700_000_000_000L,
                            endedAt = 1_700_000_010_000L,
                            deviceAddress = "AA:BB",
                            deviceName = "PCE-323",
                            weighting = "A",
                            timeWeighting = "SLOW",
                        ),
                    )
                app.container.database.measurementDao().insertAll(
                    listOf(
                        MeasurementEntity(sessionId = id, timestamp = 1_700_000_000_500L, levelDb = 45.0, weighting = "A", flags = 0),
                        MeasurementEntity(sessionId = id, timestamp = 1_700_000_005_000L, levelDb = 55.0, weighting = "A", flags = 0),
                    ),
                )
                id
            }

        val throwingExport =
            object : MessreiheExport(app) {
                override fun exportierePdf(
                    session: SessionEntity,
                    kennwerte: AkustischeKennwerte.Kennwerte,
                    ausfallbaender: List<Ausfallband>,
                    fotos: List<DokumentationsFotoEntity>,
                ): File = throw IOException("PDF Exportfehler")
            }

        composeRule.setContent {
            ProtokollDetailScreen(
                sessionId = sessionId,
                onBack = {},
                onShowSnackbar = { snackbarMeldung.set(it) },
                messreiheExport = throwingExport,
            )
        }

        composeRule.waitUntil(timeoutMillis = 15_000L) {
            composeRule.onAllNodesWithTag("btn_export_pdf").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("btn_export_pdf").performClick()

        composeRule.waitUntil(timeoutMillis = 15_000L) {
            snackbarMeldung.get() != null
        }

        assertEquals(
            composeRule.activity.getString(R.string.export_failed_message),
            snackbarMeldung.get(),
        )

        val fehlerEvents = diagnosticsReporter.recentEvents().filter { it.code == DiagnosticCode.EXPORT_FAILED }
        assertTrue("Fehler-Event EXPORT_FAILED erfasst", fehlerEvents.isNotEmpty())
    }

    @Test
    fun protokollDetailCsvExportFehlerMeldetDiagnoseUndZeigtSnackbar() {
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        val diagnosticsReporter = app.container.diagnosticsReporter
        val snackbarMeldung = AtomicReference<String?>(null)

        val sessionId =
            runBlocking {
                val id =
                    app.container.database.sessionDao().insert(
                        SessionEntity(
                            startedAt = 1_700_000_000_000L,
                            endedAt = 1_700_000_010_000L,
                            deviceAddress = "AA:BB",
                            deviceName = "PCE-323",
                            weighting = "A",
                            timeWeighting = "SLOW",
                        ),
                    )
                app.container.database.measurementDao().insertAll(
                    listOf(
                        MeasurementEntity(sessionId = id, timestamp = 1_700_000_000_500L, levelDb = 45.0, weighting = "A", flags = 0),
                        MeasurementEntity(sessionId = id, timestamp = 1_700_000_005_000L, levelDb = 55.0, weighting = "A", flags = 0),
                    ),
                )
                id
            }

        val throwingExport =
            object : MessreiheExport(app) {
                override fun exportiereCsv(
                    session: SessionEntity,
                    messwerte: List<MeasurementEntity>,
                    aggregate: List<MinuteAggregateEntity>,
                ): File = throw IOException("CSV Exportfehler")
            }

        composeRule.setContent {
            ProtokollDetailScreen(
                sessionId = sessionId,
                onBack = {},
                onShowSnackbar = { snackbarMeldung.set(it) },
                messreiheExport = throwingExport,
            )
        }

        composeRule.waitUntil(timeoutMillis = 15_000L) {
            composeRule.onAllNodesWithTag("btn_export_csv").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("btn_export_csv").performClick()

        composeRule.waitUntil(timeoutMillis = 15_000L) {
            snackbarMeldung.get() != null
        }

        assertEquals(
            composeRule.activity.getString(R.string.export_failed_message),
            snackbarMeldung.get(),
        )

        val fehlerEvents = diagnosticsReporter.recentEvents().filter { it.code == DiagnosticCode.EXPORT_FAILED }
        assertTrue("Fehler-Event EXPORT_FAILED erfasst", fehlerEvents.isNotEmpty())
    }

    // --- Exportweg 3: MainActivity (Tagesbericht ZIP / Text) ---

    @Test
    fun tagesberichtZipExportFehlerSchliesstDialogMeldetDiagnoseUndZeigtSnackbar() {
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        val diagnosticsReporter = app.container.diagnosticsReporter
        val snackbarMeldung = AtomicReference<String?>(null)

        val jetzt = System.currentTimeMillis()
        runBlocking {
            app.container.database.noiseDao().insert(
                NoiseRecord(
                    timestamp = jetzt,
                    amplitude = 100.0,
                    dbValue = 65.0,
                    filePath = "/fake/sound.wav",
                ),
            )
        }

        val throwingReportManager =
            object : ReportManager(app) {
                override fun createZipAndShare(
                    records: List<NoiseRecord>,
                    reportFile: File?,
                ): Unit = throw IOException("ZIP Fehler")
            }

        composeRule.setContent {
            NoiseProtocolApp(
                onNavigateToPlayer = {},
                onNavigateToSettings = {},
                onNavigateToMeter = {},
                onNavigateToProtokoll = {},
                onNavigateToDiagnose = {},
                onNavigateToVideo = {},
                onShowSnackbar = { msg, _, _ -> snackbarMeldung.set(msg) },
                reportManager = throwingReportManager,
            )
        }

        composeRule.onNodeWithTag("btn_overflow_menu").performClick()
        composeRule.onNodeWithTag("menu_item_tagesbericht").performClick()

        composeRule.waitUntil(timeoutMillis = 15_000L) {
            composeRule.onAllNodesWithTag("btn_report_zip").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("btn_report_zip").performClick()

        composeRule.waitUntil(timeoutMillis = 15_000L) {
            snackbarMeldung.get() != null
        }

        composeRule.onNodeWithTag("btn_report_zip").assertDoesNotExist()
        assertEquals(
            composeRule.activity.getString(R.string.export_failed_message),
            snackbarMeldung.get(),
        )

        val fehlerEvents = diagnosticsReporter.recentEvents().filter { it.code == DiagnosticCode.EXPORT_FAILED }
        assertTrue("Fehler-Event EXPORT_FAILED erfasst", fehlerEvents.isNotEmpty())
    }

    @Test
    fun tagesberichtTextExportFehlerSchliesstDialogMeldetDiagnoseUndZeigtSnackbar() {
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        val diagnosticsReporter = app.container.diagnosticsReporter
        val snackbarMeldung = AtomicReference<String?>(null)

        val jetzt = System.currentTimeMillis()
        runBlocking {
            app.container.database.noiseDao().insert(
                NoiseRecord(
                    timestamp = jetzt,
                    amplitude = 100.0,
                    dbValue = 65.0,
                    filePath = "/fake/sound.wav",
                ),
            )
        }

        val throwingReportManager =
            object : ReportManager(app) {
                override fun shareFile(file: File): Unit = throw IOException("Share Fehler")
            }

        composeRule.setContent {
            NoiseProtocolApp(
                onNavigateToPlayer = {},
                onNavigateToSettings = {},
                onNavigateToMeter = {},
                onNavigateToProtokoll = {},
                onNavigateToDiagnose = {},
                onNavigateToVideo = {},
                onShowSnackbar = { msg, _, _ -> snackbarMeldung.set(msg) },
                reportManager = throwingReportManager,
            )
        }

        composeRule.onNodeWithTag("btn_overflow_menu").performClick()
        composeRule.onNodeWithTag("menu_item_tagesbericht").performClick()

        composeRule.waitUntil(timeoutMillis = 15_000L) {
            composeRule.onAllNodesWithTag("btn_report_text_only").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("btn_report_text_only").performClick()

        composeRule.waitUntil(timeoutMillis = 15_000L) {
            snackbarMeldung.get() != null
        }

        composeRule.onNodeWithTag("btn_report_text_only").assertDoesNotExist()
        assertEquals(
            composeRule.activity.getString(R.string.export_failed_message),
            snackbarMeldung.get(),
        )

        val fehlerEvents = diagnosticsReporter.recentEvents().filter { it.code == DiagnosticCode.EXPORT_FAILED }
        assertTrue("Fehler-Event EXPORT_FAILED erfasst", fehlerEvents.isNotEmpty())
    }
}
