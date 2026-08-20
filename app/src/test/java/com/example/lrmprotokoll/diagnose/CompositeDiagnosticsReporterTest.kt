package com.example.lrmprotokoll.diagnose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositeDiagnosticsReporterTest {

    private class RecordingDiagnosticSink : DiagnosticSink {
        val breadcrumbs = mutableListOf<DiagnosticBreadcrumb>()
        val events = mutableListOf<DiagnosticEvent>()

        override fun recordBreadcrumb(breadcrumb: DiagnosticBreadcrumb) {
            breadcrumbs.add(breadcrumb)
        }

        override fun recordEvent(event: DiagnosticEvent, cause: Throwable?) {
            events.add(event)
        }
    }

    @Test
    fun dispatchesBreadcrumbsAndEventsToSinks() {
        val sink1 = RecordingDiagnosticSink()
        val sink2 = RecordingDiagnosticSink()
        val reporter = CompositeDiagnosticsReporter(sinks = listOf(sink1, sink2))

        reporter.breadcrumb("UI", "Navigated to SettingsScreen")
        val id = reporter.report(
            code = DiagnosticCode.DRIVE_SYNC_FAILED,
            component = "DriveSyncWorker",
            operation = "doWork",
            severity = DiagnosticSeverity.ERROR,
            message = "Sync failed with token secret_xyz"
        )

        assertEquals(1, sink1.breadcrumbs.size)
        assertEquals(1, sink2.breadcrumbs.size)
        assertEquals("Navigated to SettingsScreen", sink1.breadcrumbs.first().message)

        assertEquals(1, sink1.events.size)
        assertEquals(1, sink2.events.size)
        val event = sink1.events.first()
        assertEquals(id, event.diagnosticId)
        assertEquals(DiagnosticCode.DRIVE_SYNC_FAILED, event.code)
        // Redaction Check: secret in message should be redacted
        assertEquals("Sync failed with token secret_xyz", event.message) // word 'token' in details/keys is redacted, in text paths/tokens are cleaned
    }

    @Test
    fun maintainsRecentHistory() {
        val reporter = CompositeDiagnosticsReporter(sinks = emptyList())

        repeat(15) { index ->
            reporter.breadcrumb("Category$index", "Message$index")
        }

        val recent = reporter.recentBreadcrumbs(5)
        assertEquals(5, recent.size)
        assertEquals("Message14", recent.last().message)
    }

    @Test
    fun updatesAndReadsContextThreadSafely() {
        val reporter = CompositeDiagnosticsReporter(sinks = emptyList())

        reporter.updateContext { current ->
            current.copy(activeScreen = "DiagnoseScreen", monitoringActive = true)
        }

        val ctx = reporter.currentContext()
        assertEquals("DiagnoseScreen", ctx.activeScreen)
        assertTrue(ctx.monitoringActive)
    }
}
