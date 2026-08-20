package com.example.lrmprotokoll.messreihe

import com.example.lrmprotokoll.data.MeasurementEntity
import com.example.lrmprotokoll.data.MinuteAggregateEntity
import kotlin.math.log10
import kotlin.math.pow

/**
 * Eine Spalte im Pegelverlauf-Chart der Protokollansicht (M7c Aufgabe 2) - ein Zeitfenster
 * innerhalb der Session mit Min/Max/Mittel der darin liegenden Messwerte. [mittelDb] ist der
 * energetische Mittelwert (LAeq-Konvention, siehe [AkustischeKennwerte]/[PegelAggregator]), nicht
 * das arithmetische Mittel der dB-Werte.
 */
data class ChartSpalte(
    val zeitOffsetSekunden: Long,
    val minDb: Double,
    val maxDb: Double,
    val mittelDb: Double,
    val anzahl: Int,
)

/** Gemeinsamer Zwischentyp für Roh- und Aggregatwerte, damit beide über dieselbe Fenster-Logik
 * laufen (siehe [downsample]). Ein Rohwert ist ein Punkt mit `anzahl = 1`, bei dem min/max/mittel
 * alle demselben Einzelwert entsprechen. */
private data class ChartPunkt(val zeitpunkt: Long, val minDb: Double, val maxDb: Double, val mittelDb: Double, val anzahl: Int)

/**
 * Downsampling für den Rohwerte-Fall (Session noch nicht vom Retention-Job verdichtet). Ein
 * [MeasurementEntity] pro Zeitpunkt wird zu einem [ChartPunkt] mit `anzahl = 1`.
 */
fun downsampleMesswerteFuerChart(
    werte: List<MeasurementEntity>,
    sessionStart: Long,
    sessionEnde: Long,
    maxSpalten: Int = 200,
): List<ChartSpalte> = downsample(
    werte.map { ChartPunkt(it.timestamp, it.levelDb, it.levelDb, it.levelDb, 1) },
    sessionStart,
    sessionEnde,
    maxSpalten,
)

/**
 * Downsampling für den bereits verdichteten Fall ([MinuteAggregateEntity], Plan 13.2). Jede
 * Minute bringt ihr eigenes Min/Max/LAeq und ihre `sampleCount` bereits mit - [downsample]
 * gewichtet den energetischen Mittelwert beim weiteren Zusammenfassen mehrerer Minuten in eine
 * Chartspalte deshalb mit `sampleCount`, nicht gleichgewichtet pro Minute.
 */
fun downsampleAggregateFuerChart(
    aggregate: List<MinuteAggregateEntity>,
    sessionStart: Long,
    sessionEnde: Long,
    maxSpalten: Int = 200,
): List<ChartSpalte> = downsample(
    aggregate.map { ChartPunkt(it.minuteStart, it.minDb, it.maxDb, it.leqDb, it.sampleCount) },
    sessionStart,
    sessionEnde,
    maxSpalten,
)

/**
 * Teilt [sessionStart]..[sessionEnde] in bis zu [maxSpalten] gleich große Zeitfenster und fasst
 * alle Punkte je Fenster zu einer [ChartSpalte] zusammen. Leere Fenster (keine Messwerte in dem
 * Zeitraum, z.B. während eines Verbindungsausfalls) erzeugen bewusst KEINE Spalte - eine mit
 * Nullen aufgefüllte Lücke sähe wie ein gemessener Pegel von 0 dB aus, statt wie "keine Daten".
 */
private fun downsample(punkte: List<ChartPunkt>, sessionStart: Long, sessionEnde: Long, maxSpalten: Int): List<ChartSpalte> {
    if (punkte.isEmpty() || sessionEnde <= sessionStart) return emptyList()
    val fensterMillis = ((sessionEnde - sessionStart) / maxSpalten).coerceAtLeast(1)

    return punkte
        .groupBy { ((it.zeitpunkt - sessionStart) / fensterMillis).coerceAtLeast(0) }
        .toSortedMap()
        .map { (fensterIndex, gruppe) ->
            val gesamtAnzahl = gruppe.sumOf { it.anzahl }
            ChartSpalte(
                zeitOffsetSekunden = (fensterIndex * fensterMillis) / 1000,
                minDb = gruppe.minOf { it.minDb },
                maxDb = gruppe.maxOf { it.maxDb },
                mittelDb = 10.0 * log10(gruppe.sumOf { it.anzahl * 10.0.pow(it.mittelDb / 10.0) } / gesamtAnzahl),
                anzahl = gesamtAnzahl,
            )
        }
}
