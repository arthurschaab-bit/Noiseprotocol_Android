package com.example.lrmprotokoll.diagnose.acra

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.diagnose.BreadcrumbRingFile
import com.example.lrmprotokoll.diagnose.DiagnosticBreadcrumb
import com.example.lrmprotokoll.diagnose.DiagnosticSeverity
import java.time.Instant
import org.acra.builder.ReportBuilder
import org.acra.data.CrashReportData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M12 Schritt 2 Test: der Collector liefert die erwarteten Daten sowohl bei gefuellter als auch
 * bei fehlender Ringdatei (Konzept Abschnitt 7).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BreadcrumbRingCollectorTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun haengtDieZusammengefuehrteRingdateiAlsJsonAnDenReport() {
        val ringFile = BreadcrumbRingFile(context.filesDir)
        ringFile.anhaengen(
            DiagnosticBreadcrumb(
                timestamp = Instant.ofEpochMilli(1_700_000_000_000L),
                category = "BLE",
                message = "Verbindung verloren",
                level = DiagnosticSeverity.WARN,
            )
        )
        ringFile.wartenBisFertig()

        val crashReportData = CrashReportData()
        BreadcrumbRingCollector().collect(context, AcraConfig.build(), ReportBuilder(), crashReportData)

        val inhalt = crashReportData.get(BREADCRUMB_RING_REPORT_KEY) as String
        assertTrue(inhalt.contains("Verbindung verloren"))
        assertTrue(inhalt.contains("BLE"))
        assertTrue(inhalt.contains("WARN"))
    }

    @Test
    fun liefertEinLeeresArrayBeiFehlenderRingdatei() {
        // Bewusst KEINE Ringdatei angelegt - frischer filesDir ohne breadcrumbs_a/b.jsonl.
        val crashReportData = CrashReportData()

        BreadcrumbRingCollector().collect(context, AcraConfig.build(), ReportBuilder(), crashReportData)

        val inhalt = crashReportData.get(BREADCRUMB_RING_REPORT_KEY) as String
        assertEquals("[]", inhalt)
    }
}
