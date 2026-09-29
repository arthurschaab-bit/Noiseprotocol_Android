package com.example.lrmprotokoll.messreihe

import com.example.lrmprotokoll.data.NoiseRecord
import com.example.lrmprotokoll.data.SessionEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Owner-Feature-Auftrag 12.09.2026: der Protokollreiter soll nach Tagen gruppiert und nach
 * denselben Kriterien wie die Startseite ([RecordFilterState]) filterbar sein - dort aber auf
 * SESSIONS angewendet, nicht auf einzelne Ereignisse (der Protokollreiter zeigt Messungen, keine
 * Einzelereignisse).
 *
 * `onlyMeter` prueft [SessionEntity.deviceAddress] direkt (dieselbe Session-Eigenschaft, die auch
 * [com.example.lrmprotokoll.report.MessreiheExport] fuer "reiner Mikrofonlauf" verwendet) - alle
 * anderen Kriterien pruefen, ob MINDESTENS EIN Ereignis der Session dazu passt, weil sie
 * Eigenschaften einzelner Ereignisse sind ([NoiseRecord.favorite], [NoiseRecord.isQuietHour], ...),
 * keine Session-Eigenschaften.
 *
 * S-5 (F-18, F-32) vom 29.09.2026: [onlyWithEvents] ist dazugekommen. Das Kriterium existierte
 * vorher als eigener Compose-Zustand hinter einem zweiten Filter-Knopf in der TopAppBar des
 * Protokollreiters; als Teil des Modells hat es einen Chip im Panel und ist hier JVM-testbar.
 * Der ganze Zustand wird seitdem in [com.example.lrmprotokoll.data.SettingsManager] gesichert
 * (Schluessel `session_filter_*`) - vorher war er nach jedem Verlassen des Reiters weg, waehrend
 * der Filter der Startseite seinen behielt.
 */
data class SessionFilterState(
    val minDb: Float = 0.0f,
    val maxDb: Float = 120.0f,
    val onlyMeter: Boolean = false,
    val onlyCalibrated: Boolean = false,
    val onlyFavorites: Boolean = false,
    val onlyQuietHours: Boolean = false,
    val onlyWithEvents: Boolean = false,
    val labelQuery: String = "",
) {
    val istAktiv: Boolean
        get() = minDb > 0.0f ||
            maxDb < 120.0f ||
            onlyMeter ||
            onlyCalibrated ||
            onlyFavorites ||
            onlyQuietHours ||
            onlyWithEvents ||
            labelQuery.isNotBlank()

    /** Ob ueberhaupt eines der EREIGNIS-bezogenen Kriterien aktiv ist (alles ausser [onlyMeter]). */
    private val ereignisKriteriumAktiv: Boolean
        get() = minDb > 0.0f || maxDb < 120.0f || onlyCalibrated || onlyFavorites || onlyQuietHours || labelQuery.isNotBlank()

    internal fun ereignisPasst(record: NoiseRecord): Boolean {
        val pegel = record.calibratedDbA ?: record.dbValue
        if (pegel < minDb || pegel > maxDb) return false
        if (onlyCalibrated && record.calibratedDbA == null) return false
        if (onlyFavorites && !record.favorite) return false
        if (onlyQuietHours && !record.isQuietHour) return false
        val lowerLabel = labelQuery.trim().lowercase()
        if (lowerLabel.isNotBlank()) {
            val treffer = record.label?.lowercase()?.contains(lowerLabel) == true ||
                record.detectedLabel?.lowercase()?.contains(lowerLabel) == true
            if (!treffer) return false
        }
        return true
    }

    internal fun brauchtEreignispruefung(): Boolean = ereignisKriteriumAktiv
}

/**
 * Reine, JVM-testbare Filterfunktion fuer Sessions im Protokollreiter. [ereignisse] sind die
 * Aufnahmen/Ereignisse INNERHALB von [session] (Zeitraum [SessionEntity.startedAt] bis
 * [SessionEntity.endedAt]) - der Aufrufer laedt sie, diese Funktion bleibt dadurch ohne Room/DB
 * testbar.
 */
fun sessionPasstFilter(
    session: SessionEntity,
    ereignisse: List<NoiseRecord>,
    filter: SessionFilterState,
): Boolean {
    if (!filter.istAktiv) return true
    if (filter.onlyMeter && session.deviceAddress.isBlank()) return false
    // S-5/F-32: "nur mit Ereignissen" war bis hierher ein eigener Knopf in der TopAppBar mit
    // eigenem Compose-Zustand. Als Kriterium des Filtermodells gehoert die Pruefung hierher -
    // sie zaehlt nur, sie prueft keine Ereigniseigenschaft, steht also neben onlyMeter und nicht
    // in ereignisPasst().
    if (filter.onlyWithEvents && ereignisse.isEmpty()) return false
    if (!filter.brauchtEreignispruefung()) return true
    return ereignisse.any { filter.ereignisPasst(it) }
}

/**
 * Gruppiert Sessions nach Kalendertag (Format dd.MM.yyyy) fuer Tagesabschnitte im Protokollreiter -
 * dasselbe Format/Prinzip wie [gruppiereNachTag] fuer die Startseite, hier auf
 * [SessionEntity.startedAt] statt auf einzelne Ereignisse angewendet.
 */
fun gruppiereSessionsNachTag(
    sessions: List<SessionEntity>,
    locale: Locale = Locale.getDefault(),
): Map<String, List<SessionEntity>> {
    val formatter = SimpleDateFormat("dd.MM.yyyy", locale)
    return sessions.groupBy { formatter.format(Date(it.startedAt)) }
}
