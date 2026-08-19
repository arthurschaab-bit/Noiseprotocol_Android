package com.example.lrmprotokoll.diagnose

import com.example.lrmprotokoll.data.DiagnosticLogDao
import com.example.lrmprotokoll.meter.InstantSource
import java.time.Duration

/**
 * Der taegliche Bereinigungs-Job fuer das Diagnose-Log (Plan Abschnitt 6: "automatische
 * Loeschung nach 7 Tagen"). Reine Entscheidungslogik, wie [com.example.lrmprotokoll.messreihe.RetentionCoordinator]
 * fuer die Messreihe - hier gibt es aber (anders als dort) nichts zu verdichten: das Diagnose-Log
 * ist ein Debug-Hilfsmittel, kein Protokoll mit Beweiswert, ein Aggregat waere ohne Nutzen.
 */
class DiagnosticLogCleanupCoordinator(
    private val dao: DiagnosticLogDao,
    private val now: InstantSource = InstantSource.System,
    private val aufbewahrungstage: Long = 7,
) {
    suspend fun bereinige() {
        val grenze = now.now().minus(Duration.ofDays(aufbewahrungstage)).toEpochMilli()
        dao.loescheAelterAls(grenze)
    }
}
