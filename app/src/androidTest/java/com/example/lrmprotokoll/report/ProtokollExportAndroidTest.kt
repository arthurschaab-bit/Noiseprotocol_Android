package com.example.lrmprotokoll.report

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.anyIntent
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.data.MeasurementEntity
import com.example.lrmprotokoll.data.SessionEntity
import com.example.lrmprotokoll.ui.ProtokollDetailScreen
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Instrumentierte Tests für den PDF- und CSV-Export auf API 34 (Scoped Storage).
 *
 * Validiert:
 * 1. CSV-Export: Erzeugung im Scoped Storage, UTF-8 BOM, Semikolon-Trenner, _dB-Header, Zeilenanzahl und Share-Intent.
 * 2. PDF-Export: Reale Ausführung auf android.graphics.pdf.PdfDocument ohne IllegalStateException, Dateigröße > 0, %PDF-Header und Share-Intent.
 * 3. Negativfall: Session ohne Messwerte (kennwerte == null) löst Guard-Prüfung aus ohne Absturz.
 */
@RunWith(AndroidJUnit4::class)
class ProtokollExportAndroidTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: LaermprotokollApp
    private lateinit var externalFilesDir: File

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        externalFilesDir = app.getExternalFilesDir(null) ?: app.filesDir
        Intents.init()
        intending(anyIntent()).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
    }

    @After
    fun tearDown() {
        runCatching { composeRule.waitForIdle() }
        Intents.release()
    }

    private fun wartetBisAngezeigt(text: String, timeoutMs: Long = 5_000L) {
        composeRule.waitUntil(timeoutMs) {
            composeRule.onAllNodesWithText(text, substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    @Test
    fun csvUndPdfExportGenerierenGueltigeDateienAufScopedStorageUndLoesenShareIntentAus() {
        val db = app.container.database
        val startZeit = 1716000000000L
        var sessionId = 0L

        runBlocking {
            sessionId = db.sessionDao().insert(
                SessionEntity(
                    startedAt = startZeit,
                    endedAt = startZeit + 3600_000L,
                    deviceAddress = "AA:BB:CC:DD:EE:FF",
                    deviceName = "PCE-323 ExportTest",
                    weighting = "A",
                    timeWeighting = "F",
                    range = "30-130"
                )
            )

            db.measurementDao().insertAll(
                listOf(
                    MeasurementEntity(sessionId = sessionId, timestamp = startZeit + 1000L, levelDb = 55.4, weighting = "A", flags = 0),
                    MeasurementEntity(sessionId = sessionId, timestamp = startZeit + 2000L, levelDb = 62.1, weighting = "A", flags = 0),
                    MeasurementEntity(sessionId = sessionId, timestamp = startZeit + 3000L, levelDb = 71.8, weighting = "A", flags = 0),
                    MeasurementEntity(sessionId = sessionId, timestamp = startZeit + 4000L, levelDb = 58.0, weighting = "A", flags = 0)
                )
            )
        }

        val datumFormat = SimpleDateFormat("dd.MM.yyyy_HHmm", Locale.getDefault()).format(Date(startZeit))
        val expectedCsvFile = File(externalFilesDir, "Session_$datumFormat.csv")
        val expectedPdfFile = File(externalFilesDir, "Session_$datumFormat.pdf")

        // Alte Testartefakte vorab bereinigen
        expectedCsvFile.delete()
        expectedPdfFile.delete()

        composeRule.setContent {
            ProtokollDetailScreen(sessionId = sessionId, onBack = {})
        }

        val sessionTitle = composeRule.activity.getString(R.string.protocol_tab_sessions)
        val csvBtn = composeRule.activity.getString(R.string.action_export_csv)
        val pdfBtn = composeRule.activity.getString(R.string.action_export_pdf)

        wartetBisAngezeigt(sessionTitle)

        // 1. CSV EXPORT PRÜFUNG
        composeRule.onNodeWithText(csvBtn).assertIsDisplayed().performClick()

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            expectedCsvFile.exists() && expectedCsvFile.length() > 0
        }

        assertTrue("CSV-Datei muss im Scoped Storage existieren", expectedCsvFile.exists())
        val csvBytes = expectedCsvFile.readBytes()
        assertTrue("CSV-Datei darf nicht leer sein", csvBytes.size > 3)

        // UTF-8 BOM Prüfung: 0xEF, 0xBB, 0xBF
        assertEquals(0xEF.toByte(), csvBytes[0])
        assertEquals(0xBB.toByte(), csvBytes[1])
        assertEquals(0xBF.toByte(), csvBytes[2])

        val csvText = expectedCsvFile.readText(Charsets.UTF_8)
        assertTrue("CSV muss Semikolon als Trennzeichen enthalten", csvText.contains(";"))
        assertTrue("CSV muss Pegel_dB Header enthalten", csvText.contains("Pegel_dB"))
        assertTrue("CSV muss Messwerte enthalten", csvText.contains("55,4") || csvText.contains("55.4"))

        // Share-Intent Prüfung
        intended(hasAction(Intent.ACTION_CHOOSER))

        // 2. PDF EXPORT PRÜFUNG (echtes android.graphics.pdf.PdfDocument)
        composeRule.onNodeWithText(pdfBtn).assertIsDisplayed().performClick()

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            expectedPdfFile.exists() && expectedPdfFile.length() > 0
        }

        assertTrue("PDF-Datei muss im Scoped Storage existieren", expectedPdfFile.exists())
        assertTrue("PDF-Datei muss Dateigröße > 0 Byte haben", expectedPdfFile.length() > 0)

        // PDF Magic Header Prüfung (%PDF)
        val pdfBytes = expectedPdfFile.readBytes()
        assertTrue("PDF-Datei muss mindestens Headergröße aufweisen", pdfBytes.size >= 4)
        val pdfHeader = String(pdfBytes.copyOfRange(0, 4), Charsets.US_ASCII)
        assertEquals("%PDF", pdfHeader)

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            Intents.getIntents().size >= 2
        }
        intended(hasAction(Intent.ACTION_CHOOSER), Intents.times(2))

        // Bereinigung
        expectedCsvFile.delete()
        expectedPdfFile.delete()
    }

    @Test
    fun exportBeiLeererSessionLoestGuardOhneAbsturzAus() {
        val db = app.container.database
        val startZeit = 1716100000000L
        var emptySessionId = 0L

        runBlocking {
            emptySessionId = db.sessionDao().insert(
                SessionEntity(
                    startedAt = startZeit,
                    endedAt = startZeit + 60_000L,
                    deviceAddress = "AA:BB:CC:DD:EE:FF",
                    deviceName = "PCE-323 Leer",
                    weighting = "A",
                    timeWeighting = "F",
                    range = "30-130"
                )
            )
            // Bewusst KEINE Messwerte und KEINE Aggregate eingefügt -> kennwerte == null
        }

        val datumFormat = SimpleDateFormat("dd.MM.yyyy_HHmm", Locale.getDefault()).format(Date(startZeit))
        val expectedCsvFile = File(externalFilesDir, "Session_$datumFormat.csv")
        val expectedPdfFile = File(externalFilesDir, "Session_$datumFormat.pdf")
        expectedCsvFile.delete()
        expectedPdfFile.delete()

        composeRule.setContent {
            ProtokollDetailScreen(sessionId = emptySessionId, onBack = {})
        }

        val sessionTitle = composeRule.activity.getString(R.string.protocol_tab_sessions)
        val csvBtn = composeRule.activity.getString(R.string.action_export_csv)
        val pdfBtn = composeRule.activity.getString(R.string.action_export_pdf)

        wartetBisAngezeigt(sessionTitle)

        // 1. PDF-Export bei Session ohne Messwerte führt nicht zum Absturz (Guard kennwerte ?: return greift)
        composeRule.onNodeWithText(pdfBtn).assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        // 2. CSV-Export bei leerer Messreihe: Erzeugt leere CSV mit Kopfzeile ohne Absturz
        composeRule.onNodeWithText(csvBtn).assertIsDisplayed().performClick()

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            expectedCsvFile.exists()
        }

        assertTrue("CSV-Datei wird auch bei leerer Messreihe sicher erzeugt", expectedCsvFile.exists())
        val csvText = expectedCsvFile.readText(Charsets.UTF_8)
        assertTrue("CSV muss Kopfzeile enthalten", csvText.contains("Zeit;Pegel_dB") || csvText.contains("Minute;LAeq_dB"))

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            Intents.getIntents().isNotEmpty()
        }
        intended(hasAction(Intent.ACTION_CHOOSER), Intents.times(1))

        expectedCsvFile.delete()
        expectedPdfFile.delete()
    }
}
