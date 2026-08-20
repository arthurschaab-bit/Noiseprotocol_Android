package com.example.lrmprotokoll.diagnose.sentry

import com.example.lrmprotokoll.BuildConfig
import com.example.lrmprotokoll.diagnose.DiagnosticBreadcrumb
import com.example.lrmprotokoll.diagnose.DiagnosticEvent
import com.example.lrmprotokoll.diagnose.DiagnosticSeverity
import com.example.lrmprotokoll.diagnose.DiagnosticSink
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryLevel

/**
 * Remote-Sink fuer Sentry (Konzept Abschnitte 3.1 & 8).
 *
 * Uebertraegt bereinigte Fehler und Breadcrumbs nur, wenn:
 * 1. Der Build Remote-Diagnose erlaubt ([BuildConfig.DIAGNOSTICS_REMOTE_ENABLED]),
 * 2. Der Nutzer in den Einstellungen zugestimmt hat ([aktiv]), und
 * 3. Das Sentry SDK initialisiert ist ([Sentry.isEnabled]).
 */
class SentryDiagnosticSink(
    private val aktiv: () -> Boolean,
) : DiagnosticSink {

    override fun recordBreadcrumb(breadcrumb: DiagnosticBreadcrumb) {
        if (!BuildConfig.DIAGNOSTICS_REMOTE_ENABLED || !aktiv() || !Sentry.isEnabled()) return

        val sentryBreadcrumb = Breadcrumb().apply {
            category = breadcrumb.category
            message = breadcrumb.message
            level = mapSeverityToSentryLevel(breadcrumb.level)
            breadcrumb.data.forEach { (key, value) ->
                if (value != null) {
                    setData(key, value.toString())
                }
            }
        }
        Sentry.addBreadcrumb(sentryBreadcrumb)
    }

    override fun recordEvent(event: DiagnosticEvent, cause: Throwable?) {
        if (!BuildConfig.DIAGNOSTICS_REMOTE_ENABLED || !aktiv() || !Sentry.isEnabled()) return

        val sentryLevel = mapSeverityToSentryLevel(event.severity)

        if (cause != null) {
            Sentry.captureException(cause) { scope ->
                scope.level = sentryLevel
                scope.setTag("code", event.code.name)
                scope.setTag("component", event.component)
                scope.setTag("operation", event.operation)
                scope.setTag("handled", event.handled.toString())
                scope.setTag("diagnostic_id", event.diagnosticId.rawUuid)
                scope.setTag("diagnostic_id_short", event.diagnosticId.shortCode)
                if (event.statusCode != null) {
                    scope.setTag("status_code", event.statusCode)
                }
                if (event.fingerprint.isNotBlank()) {
                    scope.setFingerprint(listOf(event.fingerprint))
                }
                event.details.forEach { (key, value) ->
                    if (value != null) {
                        scope.setExtra(key, value.toString())
                    }
                }
            }
        } else {
            val msg = event.message ?: "${event.code.name} @ ${event.component}.${event.operation}"
            Sentry.captureMessage(msg, sentryLevel) { scope ->
                scope.setTag("code", event.code.name)
                scope.setTag("component", event.component)
                scope.setTag("operation", event.operation)
                scope.setTag("handled", event.handled.toString())
                scope.setTag("diagnostic_id", event.diagnosticId.rawUuid)
                scope.setTag("diagnostic_id_short", event.diagnosticId.shortCode)
                if (event.statusCode != null) {
                    scope.setTag("status_code", event.statusCode)
                }
                if (event.fingerprint.isNotBlank()) {
                    scope.setFingerprint(listOf(event.fingerprint))
                }
                event.details.forEach { (key, value) ->
                    if (value != null) {
                        scope.setExtra(key, value.toString())
                    }
                }
            }
        }
    }

    private fun mapSeverityToSentryLevel(severity: DiagnosticSeverity): SentryLevel {
        return when (severity) {
            DiagnosticSeverity.DEBUG -> SentryLevel.DEBUG
            DiagnosticSeverity.INFO -> SentryLevel.INFO
            DiagnosticSeverity.WARN -> SentryLevel.WARNING
            DiagnosticSeverity.ERROR -> SentryLevel.ERROR
            DiagnosticSeverity.FATAL -> SentryLevel.FATAL
        }
    }
}
