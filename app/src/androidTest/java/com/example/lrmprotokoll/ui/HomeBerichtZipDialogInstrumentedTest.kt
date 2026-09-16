package com.example.lrmprotokoll.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.anyIntent
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.AppContainer
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.data.NoiseRecord
import com.example.lrmprotokoll.meter.FakeMeterTransport
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checkliste Button/Screen-Coverage Phase 7d (docs Plan sorted-orbiting-crown.md). Der
 * reportTargetRecords-AlertDialog (Tagesbericht-ZIP/PDF-Teilen) in MainActivity.kt war laut Audit
 * zu 0% abgedeckt - anders als der bereits getestete Session-Export in ProtokollDetailScreen
 * (siehe ProtokollExportAndroidTest) prueft kein Test bisher diesen zweiten, unabhaengigen
 * Report-Pfad ueber [com.example.lrmprotokoll.report.ReportManager.createZipAndShare]/[shareFile],
 * erreichbar sowohl ueber den Tages-Header-Button als auch ueber das Drei-Punkt-Menue.
 *
 * Folgt demselben etablierten Muster wie ProtokollExportAndroidTest: Espresso-Intents faengt den
 * Share-Chooser ab, echte Dateien werden auf Existenz/Groesse/Magic-Header geprueft statt nur
 * Dialog-Sichtbarkeit.
 */
@RunWith(AndroidJUnit4::class)
class HomeBerichtZipDialogInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: LaermprotokollApp
    private lateinit var externalFilesDir: File

    private val tagZeit = 1_700_000_000_000L

    private val aufnahme1 = NoiseRecord(
        timestamp = tagZeit,
        amplitude = 1000.0,
        dbValue = 40.0,
        filePath = "",
        label = "ZipDialogTest1",
    )
    private val aufnahme2 = NoiseRecord(
        timestamp = tagZeit + 1_000L,
        amplitude = 1200.0,
        dbValue = 45.0,
        filePath = "",
        label = "ZipDialogTest2",
    )

    private fun datumsTag(timestamp: Long) =
        SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(timestamp))

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.setCustomContainer(AppContainer(app, FakeMeterTransport()))
        app.container.database.clearAllTables()
        externalFilesDir = app.getExternalFilesDir(null) ?: app.filesDir
        runBlocking {
            val dao = app.container.database.noiseDao()
            dao.insert(aufnahme1)
            dao.insert(aufnahme2)
        }
        Intents.init()
        intending(anyIntent()).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
    }

    @After
    fun tearDown() {
        runCatching { composeRule.waitForIdle() }
        Intents.release()
        app.container.database.clearAllTables()
        app.resetContainer()
    }

    private fun setzeInhalt() {
        composeRule.setContent {
            NoiseProtocolApp(
                onNavigateToPlayer = {},
                onNavigateToSettings = {},
                onNavigateToMeter = {},
                onNavigateToProtokoll = {},
                onNavigateToDiagnose = {},
                onNavigateToVideo = {},
            )
        }
        composeRule.waitForIdle()
    }

    @Test
    fun ueberOverflowMenueMitZipButtonErstelltPdfUndZipUndTeiltSie() {
        setzeInhalt()

        composeRule.onNodeWithTag("btn_overflow_menu").performClick()
        composeRule.onNodeWithTag("menu_item_tagesbericht").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        val beschreibung = composeRule.activity.getString(R.string.report_dialog_desc, 2)
        composeRule.onNodeWithText(beschreibung).assertIsDisplayed()

        val datum = datumsTag(tagZeit)
        val pdfFile = File(externalFilesDir, "Tagesbericht_$datum.pdf")
        val zipFile = File(externalFilesDir, "Laermprotokoll_$datum.zip")
        pdfFile.delete()
        zipFile.delete()

        composeRule.onNodeWithTag("btn_report_zip").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            pdfFile.exists() && zipFile.exists() && zipFile.length() > 0
        }

        assertTrue("PDF-Bericht muss erzeugt werden", pdfFile.exists())
        val pdfHeader = String(pdfFile.readBytes().copyOfRange(0, 4), Charsets.US_ASCII)
        assertEquals("%PDF", pdfHeader)

        assertTrue("ZIP-Paket muss erzeugt werden", zipFile.exists())
        assertTrue("ZIP-Paket darf nicht leer sein", zipFile.length() > 0)
        val zipHeader = String(zipFile.readBytes().copyOfRange(0, 2), Charsets.US_ASCII)
        assertEquals("PK", zipHeader)

        intended(hasAction(Intent.ACTION_CHOOSER))
        composeRule.onNodeWithText(beschreibung).assertDoesNotExist()

        pdfFile.delete()
        zipFile.delete()
    }

    @Test
    fun ueberTagesHeaderMitNurPdfButtonErstelltNurPdfUndTeiltEs() {
        setzeInhalt()

        val datum = datumsTag(tagZeit)
        composeRule.onNodeWithTag("home_lazy_column").performScrollToNode(hasTestTag("btn_day_report_$datum"))
        composeRule.onNodeWithTag("btn_day_report_$datum").performClick()
        composeRule.waitForIdle()

        val beschreibung = composeRule.activity.getString(R.string.report_dialog_desc, 2)
        composeRule.onNodeWithText(beschreibung).assertIsDisplayed()

        val pdfFile = File(externalFilesDir, "Tagesbericht_$datum.pdf")
        val zipFile = File(externalFilesDir, "Laermprotokoll_$datum.zip")
        pdfFile.delete()
        zipFile.delete()

        composeRule.onNodeWithTag("btn_report_text_only").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            pdfFile.exists() && pdfFile.length() > 0
        }

        assertTrue("PDF-Bericht muss erzeugt werden", pdfFile.exists())
        val pdfHeader = String(pdfFile.readBytes().copyOfRange(0, 4), Charsets.US_ASCII)
        assertEquals("%PDF", pdfHeader)
        assertTrue("Nur-PDF-Pfad darf kein ZIP erzeugen", !zipFile.exists())

        intended(hasAction(Intent.ACTION_CHOOSER))
        composeRule.onNodeWithText(beschreibung).assertDoesNotExist()

        pdfFile.delete()
    }
}
