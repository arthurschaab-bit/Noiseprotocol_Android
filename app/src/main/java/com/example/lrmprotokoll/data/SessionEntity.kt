package com.example.lrmprotokoll.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Eine zusammenhängende Überwachungsperiode mit dem Messgerät (Plan Abschnitt 8.1) - beginnt,
 * wenn [com.example.lrmprotokoll.meter.ConnectionSupervisor.start] aufgerufen wird, endet mit
 * [com.example.lrmprotokoll.meter.ConnectionSupervisor.stop]. Reconnects *innerhalb* dieser Zeit
 * erzeugen keine neue Session, sondern [ConnectionEventEntity]-Zeilen - eine Session ist die
 * Klammer um "wie lange wurde überwacht", nicht "wie lange stand die Verbindung".
 *
 * [weighting], [timeWeighting] und [range] sind nullable und bleiben `null`, solange
 * [com.example.lrmprotokoll.meter.MeterFrame.modeAssumptionConfirmed] `false` ist - siehe
 * [MeasurementEntity] für die ausführliche Begründung. Eine Session speichert ohnehin nur den
 * *zuletzt* bekannten Wert als Kontext für den Export (von
 * [com.example.lrmprotokoll.messreihe.MeasurementRecorder.stop] beim Sitzungsende aus dem
 * letzten empfangenen Frame übernommen); die verbindliche Angabe je Messwert steht in
 * [MeasurementEntity.weighting]/[MeasurementEntity.timeWeighting]/[MeasurementEntity.range].
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long?,
    val deviceAddress: String,
    val deviceName: String,
    val weighting: String?,
    val timeWeighting: String?,
    val range: String? = null,
)

/**
 * Ein einzelner Messwert innerhalb einer [SessionEntity] (Plan 8.1). Getrennt von
 * [com.example.lrmprotokoll.data.LevelSampleEntity] (M7b): Jene Tabelle ist ein bewusst
 * schlanker, temporärer Puffer nur für den Drive-Sync-Zyklus und wird nach jedem erfolgreichen
 * Sync geleert - diese Tabelle ist die dauerhafte, sitzungsbezogene Messreihe für Protokollansicht,
 * Export und Kennwerte. Beide bestehen bewusst nebeneinander (siehe M7b-PR); eine Konsolidierung
 * ist ein möglicher Folgeschritt, kein Teil dieses Auftrags.
 *
 * [weighting], [timeWeighting] und [range] bleiben `null`, solange die zugehörige Annahme
 * unbestätigt ist (siehe README "Bekannte Einschränkungen" und
 * `MeterFrame.modeAssumptionConfirmed`-KDoc): Ein erfundener oder angenommener Wert wäre hier
 * eine gespeicherte Tatsachenbehauptung, die es nicht gibt. Jede Stelle, die diese Spalten liest,
 * MUSS `null` als "unbekannt" behandeln, nicht als Fehler.
 */
@Entity(tableName = "measurements")
data class MeasurementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val timestamp: Long,
    val levelDb: Double,
    val weighting: String?,
    val flags: Int,
    val timeWeighting: String? = null,
    val range: String? = null,
)

/** Bit-Flags für [MeasurementEntity.flags] - eine Ganzzahl statt mehrerer Boolean-Spalten, damit
 * künftige Flags keine weitere Migration brauchen. */
object MeasurementFlags {
    const val HOLD_MAX = 1
    const val HOLD_MIN = 1 shl 1
    const val LARGE_JUMP = 1 shl 2
}

/**
 * Eine Verbindungsänderung innerhalb einer Session (Plan 8.1) - das Ausfallprotokoll. Eine
 * Messreihe, in der Ausfälle einfach fehlen, ist forensisch wertlos (dasselbe Argument wie bei
 * den Lücken-Zeilen im Drive-Sync, Plan 8.4.2).
 */
@Entity(tableName = "connection_events")
data class ConnectionEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val at: Long,
    val type: String,
    val reason: String?,
)

object ConnectionEventType {
    const val CONNECTED = "CONNECTED"
    const val DISCONNECTED = "DISCONNECTED"
    const val DEGRADED = "DEGRADED"
    const val RECOVERED = "RECOVERED"
}

/**
 * Verdichtetes Minutenaggregat für Rohwerte, die der Retention-Job (Plan 13.2: 90 Tage) aus
 * [MeasurementEntity] entfernt hat. `sampleCount` bleibt erhalten, damit auch nach der
 * Verdichtung sichtbar ist, wie viele Rohwerte in die Minute eingeflossen sind - eine Minute mit
 * einem einzigen Sample ist etwas anderes als eine mit 120.
 *
 * [weighting], [timeWeighting] und [range] werden von [com.example.lrmprotokoll.messreihe.RetentionCoordinator]
 * je Minute nur übernommen, wenn ALLE verdichteten Rohwerte übereinstimmen - sonst `null`, damit
 * ein Wechsel innerhalb einer Minute nicht als ein einzelner, falscher Wert verschwindet.
 */
@Entity(tableName = "minute_aggregates")
data class MinuteAggregateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val minuteStart: Long,
    val leqDb: Double,
    val maxDb: Double,
    val minDb: Double,
    val sampleCount: Int,
    val weighting: String?,
    val timeWeighting: String? = null,
    val range: String? = null,
)
