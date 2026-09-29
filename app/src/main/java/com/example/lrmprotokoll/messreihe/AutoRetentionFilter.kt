package com.example.lrmprotokoll.messreihe

import com.example.lrmprotokoll.data.NoiseRecord

/**
 * Filtert Kandidaten für die automatische Speicherbereinigung (F5 / M10).
 *
 * Regeln:
 * - Nur Aufnahmen älter als [cutoffTimestamp].
 * - Ausgeschlossen (geschützt): Favoriten (`favorite == true`), manuell gelabelte Aufnahmen (`label != null`),
 *   KI-erkannte Aufnahmen ([erkanntesLabel] liefert etwas), sowie Aufnahmen deren Muster in
 *   [gelernteMuster] vorkommt.
 *
 * Seit F-17 (PR #226) traegt eine Aufnahme, bei der die KI **nichts** erkannt hat, den Sentinel
 * [NICHT_ERKANNT_MARKER] statt `null`. Mit einer reinen `!= null`-Pruefung waere sie damit dauerhaft
 * vor der Bereinigung geschuetzt — ausgerechnet die Aufnahmen mit dem geringsten Beweiswert
 * haetten sich unloeschbar gesammelt. Deshalb entscheidet hier [erkanntesLabel], nicht `!= null`.
 */
fun ermittleAutoRetentionKandidaten(
    records: List<NoiseRecord>,
    cutoffTimestamp: Long,
    gelernteMuster: Set<String> = emptySet()
): List<NoiseRecord> {
    return records.filter { record ->
        if (record.timestamp >= cutoffTimestamp) return@filter false
        if (record.deletedAt != null) return@filter false // Bereits im Papierkorb (separater Papierkorb-Cleanup)
        if (record.favorite) return@filter false
        if (!record.label.isNullOrBlank()) return@filter false
        if (erkanntesLabel(record.detectedLabel) != null) return@filter false
        if (gelernteMuster.contains(record.filePath)) return@filter false
        true
    }
}
