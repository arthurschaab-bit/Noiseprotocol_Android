package com.example.lrmprotokoll.diagnose

/**
 * Erzeugt deterministische Fingerprints fuer die Gruppierung gleicher Ursachen
 * (Konzept Abschnitt 9.1).
 *
 * Formel: `code:component:operation:causeClass:statusCode`
 * Dynamische Werte (Zeitstempel, IDs, variable Fehlertexte) fliessen bewusst nicht in den Fingerprint ein.
 */
object DiagnosticFingerprint {

    fun create(
        code: DiagnosticCode,
        component: String,
        operation: String,
        causeClass: String? = null,
        statusCode: String? = null,
    ): String {
        val normComp = component.trim().lowercase()
        val normOp = operation.trim().lowercase()
        val normCause = (causeClass ?: "none").trim()
        val normStatus = (statusCode ?: "none").trim().lowercase()
        return "${code.name}:$normComp:$normOp:$normCause:$normStatus"
    }

    fun fromEvent(event: DiagnosticEvent): String {
        return create(
            code = event.code,
            component = event.component,
            operation = event.operation,
            causeClass = event.causeClass,
            statusCode = event.statusCode
        )
    }
}
