package com.example.lrmprotokoll.messreihe

import android.util.Log
import com.example.lrmprotokoll.data.MeasurementDao
import com.example.lrmprotokoll.data.MeasurementEntity
import com.example.lrmprotokoll.data.MinuteAggregateDao
import com.example.lrmprotokoll.data.MinuteAggregateEntity
import com.example.lrmprotokoll.meter.InstantSource
import java.time.Duration
import kotlin.math.log10
import kotlin.math.pow

private const val TAG = "RetentionCoordinator"

/**
 * Verdichtet Rohmesswerte, die aelter als [aufbewahrungstage] sind, zu Minutenaggregaten und
 * loescht danach die Rohzeilen (Plan Abschnitt 13.2: 90 Tage Rohwerte, danach Minutenaggregate -
 * Owner-Entscheidung).
 *
 * Reine Entscheidungslogik, getrennt von [RetentionWorker] (WorkManager-Glue) - dasselbe Muster
 * wie [com.example.lrmprotokoll.alert.heartbeat.HeartbeatPinger]/`HeartbeatWorker`.
 */
class RetentionCoordinator(
    private val measurementDao: MeasurementDao,
    private val minuteAggregateDao: MinuteAggregateDao,
    private val now: InstantSource = InstantSource.System,
    private val aufbewahrungstage: Long = 90,
) {

    /** Anzahl der verdichteten Minuten - vor allem fuer Tests und Logging gedacht. */
    suspend fun verdichte(): Int {
        val grenze = now.now().minus(Duration.ofDays(aufbewahrungstage)).toEpochMilli()
        val alteRohwerte = measurementDao.aelterAls(grenze)
        if (alteRohwerte.isEmpty()) return 0

        val aggregate = alteRohwerte
            .groupBy { it.sessionId to (it.timestamp / MINUTE_MS) }
            .map { (schluessel, werte) -> bildeMinutenaggregat(schluessel.first, schluessel.second, werte) }

        // Erst schreiben, DANN loeschen: bricht der Vorgang zwischen beiden Schritten ab
        // (Prozess-Tod, Speicherdruck), gehen im schlimmsten Fall doppelte Aggregate aus einem
        // wiederholten Lauf hervor - nie aber Rohdaten, die weder als Rohwert noch als Aggregat
        // ueberleben. Ein doppeltes Aggregat ist reparierbar, verlorene Messwerte nicht.
        minuteAggregateDao.insertAll(aggregate)
        measurementDao.loescheAelterAls(grenze)

        Log.i(TAG, "${alteRohwerte.size} Rohwerte zu ${aggregate.size} Minutenaggregaten verdichtet")
        return aggregate.size
    }

    private fun bildeMinutenaggregat(sessionId: Long, minutenIndex: Long, werte: List<MeasurementEntity>): MinuteAggregateEntity {
        val pegel = werte.map { it.levelDb }
        val leq = 10.0 * log10(pegel.sumOf { 10.0.pow(it / 10.0) } / pegel.size)
        val bewertungen = werte.mapNotNull { it.weighting }.toSet()

        return MinuteAggregateEntity(
            sessionId = sessionId,
            minuteStart = minutenIndex * MINUTE_MS,
            leqDb = leq,
            maxDb = pegel.max(),
            minDb = pegel.min(),
            sampleCount = werte.size,
            // Nur uebernehmen, wenn ALLE Rohwerte derselben Minute dieselbe Bewertung trugen -
            // sonst waere ein gemischtes "A und C in einer Minute" als ein einzelner Wert
            // gespeichert und die Mischung ginge unter.
            weighting = bewertungen.singleOrNull(),
        )
    }

    private companion object {
        const val MINUTE_MS = 60_000L
    }
}
