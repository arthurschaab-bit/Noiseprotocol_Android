package com.example.lrmprotokoll.diagnose.export

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.data.DiagnosticLogEntity
import com.example.lrmprotokoll.diagnose.CompositeDiagnosticsReporter
import com.example.lrmprotokoll.diagnose.DiagnosticBreadcrumb
import com.example.lrmprotokoll.diagnose.DiagnosticCode
import com.example.lrmprotokoll.diagnose.DiagnosticContext
import com.example.lrmprotokoll.diagnose.DiagnosticSeverity
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SupportBundleExporterTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun createBundleCreatesZipWithAllRequiredFiles() {
        val reporter = CompositeDiagnosticsReporter(
            sinks = emptyList(),
            initialContext = DiagnosticContext(appVersion = "1.0", buildType = "debug")
        )
        reporter.breadcrumb("BLE", "Scan started for device")
        reporter.report(
            code = DiagnosticCode.BLE_CONNECT_FAILED,
            component = "BleMeterTransport",
            operation = "connect",
            severity = DiagnosticSeverity.ERROR,
            message = "Connect timed out"
        )

        val exporter = SupportBundleExporter(context, reporter)
        val logs = listOf(
            DiagnosticLogEntity(
                id = 1,
                timestamp = System.currentTimeMillis(),
                message = "CONNECT_FAILED: Timeout nach 10s"
            )
        )

        val zipFile = exporter.createBundle(logs)
        assertTrue(zipFile.exists())
        assertTrue(zipFile.length() > 0)

        ZipFile(zipFile).use { zip ->
            val entries = zip.entries().asSequence().map { it.name }.toSet()
            assertTrue(entries.contains("summary.json"))
            assertTrue(entries.contains("events.jsonl"))
            assertTrue(entries.contains("breadcrumbs.jsonl"))
            assertTrue(entries.contains("device.json"))
            assertTrue(entries.contains("checksums.sha256"))

            val summary = zip.getInputStream(zip.getEntry("summary.json")).bufferedReader().readText()
            assertTrue(summary.contains("totalDiagnosticLogs"))
            assertTrue(summary.contains("totalBreadcrumbs"))

            val events = zip.getInputStream(zip.getEntry("events.jsonl")).bufferedReader().readText()
            assertTrue(events.contains("CONNECT_FAILED"))

            val checksums = zip.getInputStream(zip.getEntry("checksums.sha256")).bufferedReader().readText()
            assertTrue(checksums.contains("summary.json"))
            assertTrue(checksums.contains("events.jsonl"))
        }
    }

    @Test
    fun createBundleSanitizesPiiInEventsAndBreadcrumbs() {
        val reporter = CompositeDiagnosticsReporter(
            sinks = emptyList(),
            initialContext = DiagnosticContext(appVersion = "1.0", buildType = "debug")
        )
        reporter.breadcrumb(
            category = "Auth",
            message = "User user@example.com logged in with token=secret12345",
            data = mapOf("token" to "supersecret", "email" to "user@example.com")
        )

        val exporter = SupportBundleExporter(context, reporter)
        val logs = listOf(
            DiagnosticLogEntity(
                id = 1,
                timestamp = System.currentTimeMillis(),
                message = "Device AA:BB:CC:DD:EE:FF at C:\\Users\\secret\\test.txt failed for user@example.com"
            )
        )

        val zipFile = exporter.createBundle(logs)
        ZipFile(zipFile).use { zip ->
            val events = zip.getInputStream(zip.getEntry("events.jsonl")).bufferedReader().readText()
            assertTrue(events.contains("AA:BB:CC:XX:XX:XX"))
            assertTrue(events.contains("[REDACTED_EMAIL]"))
            assertTrue(!events.contains("user@example.com"))
            assertTrue(!events.contains("C:\\Users\\secret\\test.txt"))

            val breadcrumbs = zip.getInputStream(zip.getEntry("breadcrumbs.jsonl")).bufferedReader().readText()
            assertTrue(breadcrumbs.contains("[REDACTED_EMAIL]"))
            assertTrue(!breadcrumbs.contains("user@example.com"))
            assertTrue(!breadcrumbs.contains("supersecret"))
        }
    }
}
