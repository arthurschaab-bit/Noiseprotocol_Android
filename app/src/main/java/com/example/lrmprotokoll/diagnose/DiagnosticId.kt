package com.example.lrmprotokoll.diagnose

import java.util.UUID

/**
 * Eindeutige Diagnose-ID (Konzept Abschnitt 3.1 & 11.2).
 *
 * Enthaelt die volle UUID zur eindeutigen Remote-Korrelation in Sentry/Logs
 * sowie einen menschenlesbaren 8-Zeichen-Kurzcode (z. B. "8F42-1A7C") fuer Nutzersupport und UI.
 */
data class DiagnosticId(
    val rawUuid: String = UUID.randomUUID().toString(),
) {
    /** Kurzer 8-stelliger Code im Format "XXXX-XXXX" fuer UI und Nutzersupport. */
    val shortCode: String by lazy {
        val clean = rawUuid.replace("-", "").uppercase()
        if (clean.length >= 8) {
            "${clean.substring(0, 4)}-${clean.substring(4, 8)}"
        } else {
            clean
        }
    }

    override fun toString(): String = shortCode

    companion object {
        fun random(): DiagnosticId = DiagnosticId()
        fun fromString(id: String): DiagnosticId = DiagnosticId(id)
    }
}
