package com.example.lrmprotokoll.diagnose

import android.util.Log
import com.example.lrmprotokoll.data.DiagnosticLogDao
import com.example.lrmprotokoll.data.DiagnosticLogEntity
import com.example.lrmprotokoll.meter.InstantSource

private const val TAG = "DiagnosticLogger"

/**
 * Diagnose-/Rohdaten-Log (Plan Abschnitt 6): "standardmaessig aus; wenn aktiviert, nur in den
 * App-internen Speicher und mit automatischer Loeschung nach 7 Tagen" - die Loeschung ist
 * [com.example.lrmprotokoll.diagnose.DiagnosticLogCleanupCoordinator], hier nur das Schreiben.
 *
 * [aktiv] wird bei JEDEM Aufruf neu ausgewertet (kein einmalig gecachtes Flag) - liest ueblicherweise
 * direkt `SettingsManager.diagnoseLoggingAktiv`, damit ein Umschalten in den Einstellungen ohne
 * Neustart des [com.example.lrmprotokoll.meter.ConnectionSupervisor] wirkt, der diese Klasse haelt.
 *
 * Bewusst kein Rohframe-Byte-Capture (das waere die woertliche Lesart von "Rohdaten-Log"): dafuer
 * gibt es aktuell noch keinen sinnvollen Konsumenten - der Diagnose-Screen, der ein Rohframe-Log
 * anzeigen soll, ist M7 (Plan Abschnitt 9). Was M6 bereits protokolliert, sind die Ereignisse, die
 * [com.example.lrmprotokoll.meter.ConnectionSupervisor] ohnehin erkennt (Datenstillstand, hohe
 * Fehlerrate, Kadenz-Abweichung, gescheiterte Verbindungsversuche) - genau die Rohdaten, die der
 * geplante "Reconnect-Zaehler"/"Decode-Fehlerrate"-Teil des Diagnose-Screens braucht.
 */
class DiagnosticLogger(
    private val dao: DiagnosticLogDao,
    private val now: InstantSource = InstantSource.System,
    private val aktiv: () -> Boolean,
) {
    suspend fun protokolliere(nachricht: String) {
        if (!aktiv()) return
        runCatching {
            dao.insert(DiagnosticLogEntity(timestamp = now.now().toEpochMilli(), message = nachricht))
        }.onFailure { Log.w(TAG, "Konnte Diagnose-Eintrag nicht schreiben", it) }
    }
}
