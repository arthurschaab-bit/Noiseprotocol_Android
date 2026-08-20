package com.example.lrmprotokoll.diagnose

import java.time.Instant

/**
 * Typisiertes Diagnose-Ereignis (Konzept Abschnitt 6).
 */
data class DiagnosticEvent(
    val diagnosticId: DiagnosticId = DiagnosticId.random(),
    val timestampUtc: Instant = Instant.now(),
    val elapsedRealtimeMs: Long = 0L,
    val code: DiagnosticCode,
    val severity: DiagnosticSeverity = DiagnosticSeverity.ERROR,
    val handled: Boolean = true,
    val retryable: Boolean = false,
    val userVisible: Boolean = false,
    val component: String,
    val operation: String,
    val causeClass: String? = null,
    val message: String? = null,
    val statusCode: String? = null,
    val details: Map<String, Any?> = emptyMap(),
    val fingerprint: String = "",
    val occurrenceCount: Int = 1,
)
