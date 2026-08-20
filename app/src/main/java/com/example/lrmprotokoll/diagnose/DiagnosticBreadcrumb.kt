package com.example.lrmprotokoll.diagnose

import java.time.Instant

/**
 * Strukturierte Breadcrumb zur Nachvollziehbarkeit von Ablauefen unmittelbar vor einem Fehler
 * (Konzept Abschnitt 7).
 */
data class DiagnosticBreadcrumb(
    val timestamp: Instant = Instant.now(),
    val category: String,
    val message: String,
    val level: DiagnosticSeverity = DiagnosticSeverity.INFO,
    val data: Map<String, Any?> = emptyMap(),
)
