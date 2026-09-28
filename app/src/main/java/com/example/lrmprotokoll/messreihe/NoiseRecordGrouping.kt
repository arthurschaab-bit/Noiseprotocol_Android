package com.example.lrmprotokoll.messreihe

import com.example.lrmprotokoll.data.NoiseRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Gespeicherter Wert fuer eine erfolgreiche Inferenz ohne Treffer oberhalb der Schwelle. */
const val NICHT_ERKANNT_LABEL = "Nicht erkannt"

enum class KlassifizierungsStatus { DEAKTIVIERT, AUSSTEHEND, NICHT_ERKANNT, ERKANNT }

/** Ohne Audiopfad gibt es keinen Klassifizierungsstatus. */
fun klassifizierungsStatus(record: NoiseRecord, aiMode: String): KlassifizierungsStatus? {
    if (record.filePath.isBlank()) return null
    return when (record.detectedLabel) {
        NICHT_ERKANNT_LABEL, "Not recognized" -> KlassifizierungsStatus.NICHT_ERKANNT
        null -> if (aiMode == "OFF") KlassifizierungsStatus.DEAKTIVIERT else KlassifizierungsStatus.AUSSTEHEND
        else -> KlassifizierungsStatus.ERKANNT
    }
}

/**
 * Gruppiert Aufnahmen nach Kalendertag (Format dd.MM.yyyy) für die Tagesabschnitte auf dem
 * Home-Screen. Reihenfolge der Gruppen folgt der Reihenfolge der ersten je Tag angetroffenen
 * Aufnahme in [records] (i.d.R. bereits absteigend nach Zeit sortiert aus dem DAO).
 */
fun gruppiereNachTag(
    records: List<NoiseRecord>,
    locale: Locale = Locale.getDefault(),
): Map<String, List<NoiseRecord>> {
    val formatter = SimpleDateFormat("dd.MM.yyyy", locale)
    return records.groupBy { formatter.format(Date(it.timestamp)) }
}

/**
 * Aufnahmen, für die eine KI-Nachklassifizierung sinnvoll ist: weder KI- noch manuelles Label
 * vorhanden, und es existiert überhaupt ein Audiopfad (rein Messgerät-getriggerte Einträge ohne
 * Aufnahme haben keinen).
 */
fun unklassifizierteAufnahmen(records: List<NoiseRecord>): List<NoiseRecord> =
    records.filter { it.detectedLabel == null && it.label == null && it.filePath.isNotBlank() }
