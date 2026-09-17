package com.example.lrmprotokoll.diagnose.acra

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.acra.ReportField
import org.acra.data.CrashReportData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Schritt 1 Test 2: der vorlaeufige Sender schreibt bei einem Fake-[CrashReportData] eine Datei
 * mit erwartetem Namensschema in `support_outbox/` (Konzept 4.2, "kein Netzwerk, kein Drive,
 * kein Worker").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SupportOutboxReportSenderTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun schreibtEineDateiInDenSupportOutbox() {
        val outboxDir = File(context.filesDir, SUPPORT_OUTBOX_DIR)
        outboxDir.deleteRecursively()

        val report = CrashReportData().apply {
            put(ReportField.STACK_TRACE, "java.lang.RuntimeException: Testabsturz")
            put(ReportField.APP_VERSION_NAME, "1.0")
        }

        SupportOutboxReportSender().send(context, report)

        assertTrue("support_outbox/ muss angelegt werden", outboxDir.isDirectory)
        val dateien = outboxDir.listFiles().orEmpty()
        assertEquals(1, dateien.size)
        assertTrue(
            "Dateiname muss dem Schema <Zeitstempel>_acra_report.json entsprechen, war: ${dateien[0].name}",
            dateien[0].name.matches(Regex("\\d{8}_\\d{6}_acra_report\\.json")),
        )
        assertTrue(dateien[0].readText().contains("Testabsturz"))
    }

    @Test
    fun factoryLiefertEinenSupportOutboxReportSender() {
        val factory = SupportOutboxReportSenderFactory()
        val sender = factory.create(context, AcraConfig.build())
        assertTrue(sender is SupportOutboxReportSender)
    }
}
