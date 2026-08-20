package com.example.lrmprotokoll.diagnose

/**
 * Zentrales Interface fuer die app-weite Fehler- und Diagnosereporting-Schicht (Konzept Abschnitt 3.1).
 */
interface DiagnosticsReporter {
    fun breadcrumb(
        category: String,
        message: String,
        data: Map<String, Any?> = emptyMap(),
        level: DiagnosticSeverity = DiagnosticSeverity.INFO,
    )

    fun breadcrumb(breadcrumb: DiagnosticBreadcrumb)

    fun report(event: DiagnosticEvent, cause: Throwable? = null): DiagnosticId

    fun report(
        code: DiagnosticCode,
        component: String,
        operation: String,
        severity: DiagnosticSeverity = DiagnosticSeverity.ERROR,
        handled: Boolean = true,
        retryable: Boolean = false,
        userVisible: Boolean = false,
        cause: Throwable? = null,
        message: String? = null,
        statusCode: String? = null,
        details: Map<String, Any?> = emptyMap(),
    ): DiagnosticId

    fun updateContext(update: (DiagnosticContext) -> DiagnosticContext)
    fun currentContext(): DiagnosticContext
    fun recentBreadcrumbs(limit: Int = 50): List<DiagnosticBreadcrumb>
    fun recentEvents(limit: Int = 50): List<DiagnosticEvent>
}
