package com.example.lrmprotokoll.diagnose

import android.os.SystemClock
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Standardimplementierung des Reporters, der Ereignisse bereinigt, gruppiert,
 * prueft und an alle registrierten Sinks verteilt (Konzept Abschnitt 3.1 & 8).
 */
class CompositeDiagnosticsReporter(
    private val sinks: List<DiagnosticSink> = emptyList(),
    private val rateLimiter: DiagnosticRateLimiter = DiagnosticRateLimiter(),
    initialContext: DiagnosticContext = DiagnosticContext(),
    private val maxHistorySize: Int = 100,
    private val elapsedRealtimeProvider: () -> Long = {
        try {
            SystemClock.elapsedRealtime()
        } catch (_: Throwable) {
            System.currentTimeMillis()
        }
    },
) : DiagnosticsReporter {

    private val lock = Any()
    private var context: DiagnosticContext = initialContext
    private val breadcrumbsHistory = CopyOnWriteArrayList<DiagnosticBreadcrumb>()
    private val eventsHistory = CopyOnWriteArrayList<DiagnosticEvent>()

    override fun breadcrumb(category: String, message: String, data: Map<String, Any?>, level: DiagnosticSeverity) {
        breadcrumb(
            DiagnosticBreadcrumb(
                timestamp = Instant.now(),
                category = category,
                message = message,
                level = level,
                data = data,
            )
        )
    }

    override fun breadcrumb(breadcrumb: DiagnosticBreadcrumb) {
        val clean = DiagnosticRedactor.redactBreadcrumb(breadcrumb)
        synchronized(lock) {
            breadcrumbsHistory.add(clean)
            if (breadcrumbsHistory.size > maxHistorySize) {
                breadcrumbsHistory.removeAt(0)
            }
        }
        for (sink in sinks) {
            runCatching { sink.recordBreadcrumb(clean) }
        }
    }

    override fun report(
        code: DiagnosticCode,
        component: String,
        operation: String,
        severity: DiagnosticSeverity,
        handled: Boolean,
        retryable: Boolean,
        userVisible: Boolean,
        cause: Throwable?,
        message: String?,
        statusCode: String?,
        details: Map<String, Any?>,
    ): DiagnosticId {
        val id = DiagnosticId.random()
        val causeClassName = cause?.let { it.javaClass.name }
        val rawEvent = DiagnosticEvent(
            diagnosticId = id,
            timestampUtc = Instant.now(),
            elapsedRealtimeMs = elapsedRealtimeProvider(),
            code = code,
            severity = severity,
            handled = handled,
            retryable = retryable,
            userVisible = userVisible,
            component = component,
            operation = operation,
            causeClass = causeClassName,
            message = message ?: cause?.message,
            statusCode = statusCode,
            details = details,
            fingerprint = DiagnosticFingerprint.create(code, component, operation, causeClassName, statusCode),
        )
        report(rawEvent, cause)
        return id
    }

    override fun report(event: DiagnosticEvent, cause: Throwable?): DiagnosticId {
        val cleanEvent = DiagnosticRedactor.redactEvent(event)
        val limitResult = rateLimiter.checkAndRecord(cleanEvent)

        synchronized(lock) {
            eventsHistory.add(cleanEvent)
            if (eventsHistory.size > maxHistorySize) {
                eventsHistory.removeAt(0)
            }
        }

        if (limitResult.decision == DiagnosticRateLimiter.Decision.EMIT_NOW) {
            val eventToEmit = if (limitResult.occurrenceCount > 1) {
                cleanEvent.copy(occurrenceCount = limitResult.occurrenceCount)
            } else {
                cleanEvent
            }
            for (sink in sinks) {
                runCatching { sink.recordEvent(eventToEmit, cause) }
            }
        }

        return cleanEvent.diagnosticId
    }

    override fun updateContext(update: (DiagnosticContext) -> DiagnosticContext) {
        synchronized(lock) {
            context = update(context)
        }
    }

    override fun currentContext(): DiagnosticContext {
        synchronized(lock) {
            return context
        }
    }

    override fun recentBreadcrumbs(limit: Int): List<DiagnosticBreadcrumb> {
        val items = breadcrumbsHistory.toList()
        return items.takeLast(limit)
    }

    override fun recentEvents(limit: Int): List<DiagnosticEvent> {
        val items = eventsHistory.toList()
        return items.takeLast(limit)
    }
}
