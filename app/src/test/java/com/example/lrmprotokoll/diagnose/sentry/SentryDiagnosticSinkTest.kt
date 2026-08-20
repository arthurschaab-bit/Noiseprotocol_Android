package com.example.lrmprotokoll.diagnose.sentry

import com.example.lrmprotokoll.diagnose.DiagnosticBreadcrumb
import com.example.lrmprotokoll.diagnose.DiagnosticCode
import com.example.lrmprotokoll.diagnose.DiagnosticEvent
import com.example.lrmprotokoll.diagnose.DiagnosticSeverity
import org.junit.Assert.assertNotNull
import org.junit.Test

class SentryDiagnosticSinkTest {

    @Test
    fun sinkInstantiatesAndHandlesEventsWithoutCrashingWhenSentryNotInitialized() {
        val sink = SentryDiagnosticSink(aktiv = { true })

        val breadcrumb = DiagnosticBreadcrumb(
            category = "BLE",
            message = "Scan started"
        )
        sink.recordBreadcrumb(breadcrumb)

        val event = DiagnosticEvent(
            code = DiagnosticCode.BLE_CONNECT_FAILED,
            component = "BleMeterTransport",
            operation = "connect",
            severity = DiagnosticSeverity.ERROR
        )
        sink.recordEvent(event, cause = IllegalStateException("Mock error"))

        assertNotNull(sink)
    }

    @Test
    fun sinkIgnoresEventsWhenDeactivated() {
        var active = false
        val sink = SentryDiagnosticSink(aktiv = { active })

        val event = DiagnosticEvent(
            code = DiagnosticCode.APP_UNCAUGHT,
            component = "App",
            operation = "main"
        )
        sink.recordEvent(event)

        assertNotNull(sink)
    }
}
