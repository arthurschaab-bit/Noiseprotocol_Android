package com.example.lrmprotokoll.messreihe

import com.example.lrmprotokoll.data.NoiseRecord

data class RecordFilterState(
    val query: String = "",
    val minDb: Float = 0.0f,
    val maxDb: Float = 120.0f,
    val onlyMeter: Boolean = false,
    val onlyCalibrated: Boolean = false,
    val onlyFavorites: Boolean = false,
    val onlyQuietHours: Boolean = false,
) {
    val istAktiv: Boolean
        get() = query.isNotBlank() ||
            minDb > 0.0f ||
            maxDb < 120.0f ||
            onlyMeter ||
            onlyCalibrated ||
            onlyFavorites ||
            onlyQuietHours
}

/**
 * Reine, JVM-testbare Filterfunktion für Audio-Aufnahmen (F2 / M10).
 */
fun filtereNoiseRecords(
    records: List<NoiseRecord>,
    filter: RecordFilterState
): List<NoiseRecord> {
    if (!filter.istAktiv) return records

    val lowerQuery = filter.query.trim().lowercase()

    return records.filter { record ->
        // Text-Suche über Label, DetectedLabel und Notizen
        val textMatch = if (lowerQuery.isBlank()) true else {
            val labelMatch = record.label?.lowercase()?.contains(lowerQuery) == true
            val detectedMatch = record.detectedLabel?.lowercase()?.contains(lowerQuery) == true
            val notesMatch = record.notes?.lowercase()?.contains(lowerQuery) == true
            labelMatch || detectedMatch || notesMatch
        }
        if (!textMatch) return@filter false

        // Pegel-Filter (unter Berücksichtigung des kalibrierten oder unkalibrierten Pegels)
        val pegel = record.calibratedDbA ?: record.dbValue
        if (pegel < filter.minDb || pegel > filter.maxDb) return@filter false

        // Schalter-Filter
        if (filter.onlyMeter && !record.meterConnected) return@filter false
        if (filter.onlyCalibrated && record.calibratedDbA == null) return@filter false
        if (filter.onlyFavorites && !record.favorite) return@filter false
        if (filter.onlyQuietHours && !record.isQuietHour) return@filter false

        true
    }
}
