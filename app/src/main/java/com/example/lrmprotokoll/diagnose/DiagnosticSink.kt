package com.example.lrmprotokoll.diagnose

import com.example.lrmprotokoll.data.DiagnosticLogDao
import com.example.lrmprotokoll.data.DiagnosticLogEntity
import com.example.lrmprotokoll.meter.InstantSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Ziel-Schnittstelle fuer Diagnoseereignisse und Breadcrumbs (Konzept Abschnitt 3.1).
 */
interface DiagnosticSink {
    fun recordBreadcrumb(breadcrumb: DiagnosticBreadcrumb)
    fun recordEvent(event: DiagnosticEvent, cause: Throwable? = null)
}

/**
 * Lokaler Sink: speichert Diagnoseereignisse in der Room-Tabelle `diagnostic_log_entries`
 * ueber den vorhandenen [DiagnosticLogDao].
 */
class LocalDiagnosticSink(
    private val dao: DiagnosticLogDao,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val now: InstantSource = InstantSource.System,
    private val aktiv: () -> Boolean,
) : DiagnosticSink {

    override fun recordBreadcrumb(breadcrumb: DiagnosticBreadcrumb) {
        // Breadcrumbs werden lokal nur optional protokolliert, falls aktiv
        if (!aktiv()) return
        scope.launch {
            runCatching {
                dao.insert(
                    DiagnosticLogEntity(
                        timestamp = breadcrumb.timestamp.toEpochMilli(),
                        message = "[${breadcrumb.level}] ${breadcrumb.category}: ${breadcrumb.message}"
                    )
                )
            }
        }
    }

    override fun recordEvent(event: DiagnosticEvent, cause: Throwable?) {
        if (!aktiv()) return
        scope.launch {
            val msg = buildString {
                append("[${event.severity}] ${event.code} (${event.diagnosticId.shortCode}) @ ${event.component}.${event.operation}")
                if (!event.message.isNullOrBlank()) {
                    append(": ${event.message}")
                }
                if (cause != null) {
                    append(" -> ${cause.javaClass.simpleName}: ${cause.message}")
                }
                if (event.occurrenceCount > 1) {
                    append(" (${event.occurrenceCount}x gebuendelt)")
                }
            }
            runCatching {
                dao.insert(
                    DiagnosticLogEntity(
                        timestamp = event.timestampUtc.toEpochMilli(),
                        message = msg
                    )
                )
            }
        }
    }
}

/**
 * No-Op Sink fuer Tests und Default-Betrieb ohne externe Sinks.
 */
class NoOpDiagnosticSink : DiagnosticSink {
    override fun recordBreadcrumb(breadcrumb: DiagnosticBreadcrumb) = Unit
    override fun recordEvent(event: DiagnosticEvent, cause: Throwable?) = Unit
}
