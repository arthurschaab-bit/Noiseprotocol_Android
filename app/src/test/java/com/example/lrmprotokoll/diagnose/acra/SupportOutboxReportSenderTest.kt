package com.example.lrmprotokoll.diagnose.acra

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.zip.ZipFile
import org.acra.ReportField
import org.acra.data.CrashReportData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    private val context: Context get() = ApplicationProvider.getApplicationContext()

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
    fun factoryLiefertEinenSupportOutboxReportSender() {
        val factory = SupportOutboxReportSenderFactory()
        val sender = factory.create(context, AcraConfig.build())
        assertTrue(sender is SupportOutboxReportSender)
    }
}
