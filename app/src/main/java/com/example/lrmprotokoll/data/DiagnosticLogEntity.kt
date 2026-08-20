package com.example.lrmprotokoll.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Diagnose-/Rohdaten-Log (Plan Abschnitt 6): "standardmaessig aus; wenn aktiviert, nur in den
 * App-internen Speicher und mit automatischer Loeschung nach 7 Tagen". Bewusst eine eigene,
 * schlanke Tabelle statt [com.example.lrmprotokoll.messreihe.ConnectionEventEntity] (M4)
 * wiederzuverwenden: Verbindungsereignisse sind Teil des dauerhaften Messreihen-Protokolls (90
 * Tage, dann Aggregat), dieses Log ist ein rein technisches, standardmaessig abgeschaltetes
 * Debug-Hilfsmittel mit fester 7-Tage-Frist - beide Aufbewahrungsregeln in einer Tabelle zu
 * mischen waere fehleranfaellig. Wird ueber SQLite (also "App-interner Speicher", kein externer
 * Speicher, keine Datei ausserhalb der Sandbox) abgelegt.
 *
 * Die Anzeige (Diagnose-Screen: Zustandsautomat live, Reconnect-Zaehler, Decode-Fehlerrate,
 * Rohframe-Log, Sync-Historie) ist M7 (Plan Abschnitt 9) - diese Tabelle ist bewusst nur die
 * Ablage, kein Rohframe-Byte-Capture: siehe [com.example.lrmprotokoll.diagnose.DiagnosticLogger]-
 * KDoc fuer die Abwaegung, welche Ereignisse M6 bereits protokolliert.
 */
@Entity(tableName = "diagnostic_log_entries")
data class DiagnosticLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val message: String,
)
