package com.example.lrmprotokoll.diagnose.acra

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.lrmprotokoll.data.SettingsManager
import java.io.File
import java.util.zip.ZipFile
import org.acra.ReportField
import org.acra.data.CrashReportData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Schritt 1 Test 2, seit Schritt 5 aktualisiert: der Sender baut bei einem Fake-[CrashReportData]
 * ein vollstaendiges Support-Bundle und legt es mit erwartetem Namensschema in
 * `support_outbox/` ab (Konzept 4.2/4.6).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SupportOutboxReportSenderTest {

    /** Muss mit der privaten `WORK_NAME`-Konstante in [com.example.lrmprotokoll.diagnose.export.SupportBundleUploadPlanung] uebereinstimmen. */
    private val uploadWorkName = "support_bundle_upload"

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun aufbauen() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        SettingsManager(context).absturzAutoUploadAktiv = true
    }

    @After
    fun aufraeumen() {
        SettingsManager(context).absturzAutoUploadAktiv = true
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test
    fun bautEinBundleUndLegtEsInDenSupportOutbox() {
        val outboxDir = File(context.filesDir, SUPPORT_OUTBOX_DIR)
        outboxDir.deleteRecursively()

        val report = CrashReportData().apply {
            put(ReportField.STACK_TRACE, "java.lang.RuntimeException: Testabsturz")
            put(ReportField.APP_VERSION_NAME, "1.0")
            put(ReportField.THREAD_DETAILS, "main: RUNNABLE")
            put(ReportField.LOGCAT, "09-17 12:00:00.000 D Test: Testzeile")
        }

        SupportOutboxReportSender().send(context, report)

        assertTrue("support_outbox/ muss angelegt werden", outboxDir.isDirectory)
        val dateien = outboxDir.listFiles().orEmpty()
        assertEquals(1, dateien.size)
        assertTrue(
            "Dateiname muss dem Schema JJJJ-MM-TT_HHMMSS_absturz.zip entsprechen (Konzept 4.6), war: ${dateien[0].name}",
            dateien[0].name.matches(Regex("\\d{4}-\\d{2}-\\d{2}_\\d{6}_absturz\\.zip")),
        )
        ZipFile(dateien[0]).use { zip ->
            assertTrue(zip.getEntry("crash/acra_report.json") != null)
            assertTrue(zip.getEntry("crash/threads.txt") != null)
            val acraReport = zip.getInputStream(zip.getEntry("crash/acra_report.json")).bufferedReader().readText()
            assertTrue(acraReport.contains("Testabsturz"))
        }
    }

    @Test
    fun beiDeaktiviertemAutoUploadEntstehtDasBundleTrotzdemAberKeinUploadWirdEingereiht() {
        // M12 Schritt 8 (Konzept Aufgabe 2): der Schalter betrifft nur den automatischen Upload,
        // die Bundle-Erstellung (das eigentliche Sicherheitsnetz) laeuft unveraendert weiter.
        val outboxDir = File(context.filesDir, SUPPORT_OUTBOX_DIR)
        outboxDir.deleteRecursively()
        SettingsManager(context).absturzAutoUploadAktiv = false

        val report = CrashReportData().apply {
            put(ReportField.STACK_TRACE, "java.lang.RuntimeException: Testabsturz")
        }

        SupportOutboxReportSender().send(context, report)

        assertEquals(
            "Das Bundle muss trotz ausgeschaltetem Auto-Upload lokal entstehen",
            1,
            outboxDir.listFiles().orEmpty().size,
        )
        val ausstehendeUploads = WorkManager.getInstance(context).getWorkInfosForUniqueWork(uploadWorkName).get()
        assertTrue("Bei ausgeschaltetem Schalter darf kein Upload eingereiht werden", ausstehendeUploads.isEmpty())
    }

    @Test
    fun beiAktiviertemAutoUploadStandardWirdDerUploadEingereiht() {
        val report = CrashReportData().apply {
            put(ReportField.STACK_TRACE, "java.lang.RuntimeException: Testabsturz")
        }

        SupportOutboxReportSender().send(context, report)

        val ausstehendeUploads = WorkManager.getInstance(context).getWorkInfosForUniqueWork(uploadWorkName).get()
        assertTrue("Bei aktiviertem Schalter (Standard) muss der Upload eingereiht werden", ausstehendeUploads.isNotEmpty())
    }

    /**
     * Bugfix docs/PROMPT_FIX_LAUFZEITZUSTAND_ABSTURZ.md Schritt 2: end-to-end - enthaelt der
     * ACRA-Report den LAUFZEITZUSTAND-Schluessel (von LaufzeitzustandCollector gesetzt), muss er
     * im gebauten Bundle als eigene Datei landen.
     */
    @Test
    fun bautEinBundleMitLaufzeitzustandWennImReportVorhanden() {
        val outboxDir = File(context.filesDir, SUPPORT_OUTBOX_DIR)
        outboxDir.deleteRecursively()

        val report =
            CrashReportData().apply {
                put(ReportField.STACK_TRACE, "java.lang.OutOfMemoryError: Testabsturz")
                put(LAUFZEITZUSTAND_REPORT_KEY, "{\"aufnahmeAktiv\":true,\"heapMaxBytes\":402653184}")
            }

        SupportOutboxReportSender().send(context, report)

        val dateien = outboxDir.listFiles().orEmpty()
        assertEquals(1, dateien.size)
        ZipFile(dateien[0]).use { zip ->
            assertTrue(zip.getEntry("crash/laufzeitzustand_beim_absturz.json") != null)
            val inhalt = zip.getInputStream(zip.getEntry("crash/laufzeitzustand_beim_absturz.json")).bufferedReader().readText()
            assertTrue(inhalt.contains("aufnahmeAktiv"))
            assertTrue(inhalt.contains("402653184"))
        }
    }

    @Test
    fun factoryLiefertEinenSupportOutboxReportSender() {
        val factory = SupportOutboxReportSenderFactory()
        val sender = factory.create(context, AcraConfig.build())
        assertTrue(sender is SupportOutboxReportSender)
    }
}
