package com.example.lrmprotokoll.messreihe

import com.example.lrmprotokoll.data.MeasurementEntity
import com.example.lrmprotokoll.data.MinuteAggregateEntity
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Die akustischen Kennwerte je Session oder Zeitraum (Plan Abschnitt 8.3). Auf Abruf berechnet
 * ueber die gespeicherten [MeasurementEntity]-Zeilen, NICHT als laufendes Histogramm waehrend
 * der Aufzeichnung mitgefuehrt, wie Plan 8.3 es als Ausbaustufe nennt ("streaming-faehig via
 * Histogramm in 0,1-dB-Bins"). Bewusste Vereinfachung: Bei den ueblichen Sitzungslaengen ist
 * das Sortieren der Rohwerte auf einem Telefon in Millisekunden erledigt, und eine
 * On-Demand-Berechnung ist erheblich einfacher zu pruefen als ein laufend aktualisiertes
 * Histogramm. Wird das bei sehr langen Sitzungen spuerbar, ist das Histogramm ein moeglicher
 * Folgeschritt.
 */
object AkustischeKennwerte {

    data class Kennwerte(
        val leqDb: Double?,
        val maxDb: Double?,
        val minDb: Double?,
        /** Level, das nur 10 % der Zeit ueberschritten wird - der laute Ausreisser-Bereich. */
        val l10Db: Double?,
        val l50Db: Double?,
        /** Level, das 90 % der Zeit ueberschritten wird - naeherungsweise der Grundgeraeuschpegel. */
        val l90Db: Double?,
        /** Wie lange der Pegel ueber der uebergebenen Schwelle lag, aus den Zeitabstaenden
         * zwischen aufeinanderfolgenden Messwerten - nicht einfach Anzahl mal Intervall, weil die
         * Werte unregelmaessig eintreffen (Plan 2.1: ~515 ms beim PCE-323, aber nie exakt). */
        val ueberschreitungsdauerMs: Long,
        val sampleCount: Int,
    )

    private val LEER = Kennwerte(
        leqDb = null, maxDb = null, minDb = null, l10Db = null, l50Db = null, l90Db = null,
        ueberschreitungsdauerMs = 0, sampleCount = 0,
    )

    /**
     * [ueberschreitungsSchwelleDb] ist optional, weil Kennwerte oft ohne eine konkrete Schwelle
     * gebraucht werden (z. B. fuer die Protokollansicht) - `null` liefert einfach 0 ms zurueck,
     * statt eine willkuerliche Schwelle anzunehmen.
     */
    fun berechne(messwerte: List<MeasurementEntity>, ueberschreitungsSchwelleDb: Double? = null): Kennwerte {
        if (messwerte.isEmpty()) return LEER

        val nachZeitSortiert = messwerte.sortedBy { it.timestamp }
        val pegel = nachZeitSortiert.map { it.levelDb }

        val leq = 10.0 * log10(pegel.sumOf { 10.0.pow(it / 10.0) } / pegel.size)
        val nachPegelSortiert = pegel.sorted()

        return Kennwerte(
            leqDb = leq,
            maxDb = nachPegelSortiert.last(),
            minDb = nachPegelSortiert.first(),
            l10Db = perzentil(nachPegelSortiert, 0.90),
            l50Db = perzentil(nachPegelSortiert, 0.50),
            l90Db = perzentil(nachPegelSortiert, 0.10),
            ueberschreitungsdauerMs = ueberschreitungsSchwelleDb
                ?.let { ueberschreitungsdauer(nachZeitSortiert, it) } ?: 0L,
            sampleCount = pegel.size,
        )
    }

    /** LN = "wird N % der Zeit ueberschritten" - L10 also der HOHE Pegel (90. Perzentil), L90
     * der NIEDRIGE (10. Perzentil). Nearest-Rank-Methode: einfach, deterministisch, ohne
     * Interpolationsartefakte bei kleinen Stichproben. */
    private fun perzentil(nachPegelSortiert: List<Double>, anteil: Double): Double {
        if (nachPegelSortiert.size == 1) return nachPegelSortiert[0]
        val index = (anteil * (nachPegelSortiert.size - 1)).roundToInt()
            .coerceIn(0, nachPegelSortiert.size - 1)
        return nachPegelSortiert[index]
    }

    /**
     * Näherungsweise Kennwerte, wenn die Rohwerte einer Session bereits durch den Retention-Job
     * (Plan 13.2) zu [MinuteAggregateEntity] verdichtet wurden - für die Protokollansicht (Plan
     * Abschnitt 9), die auch für ältere Sessions noch etwas Sinnvolles anzeigen soll. Ohne
     * Rohwerte lassen sich L10/L50/L90 und die Überschreitungsdauer nicht mehr rekonstruieren
     * (bleiben `null`/0) - Leq bleibt exakt (energetischer Mittelwert über die bereits
     * energetisch gemittelten Minuten-LAeq-Werte), Max/Min bleiben exakt, weil pro Minute
     * mitgeführt.
     */
    fun ausAggregaten(aggregate: List<MinuteAggregateEntity>): Kennwerte {
        if (aggregate.isEmpty()) return LEER
        val leq = 10.0 * log10(aggregate.sumOf { 10.0.pow(it.leqDb / 10.0) } / aggregate.size)
        return Kennwerte(
            leqDb = leq,
            maxDb = aggregate.maxOf { it.maxDb },
            minDb = aggregate.minOf { it.minDb },
            l10Db = null,
            l50Db = null,
            l90Db = null,
            ueberschreitungsdauerMs = 0L,
            sampleCount = aggregate.sumOf { it.sampleCount },
        )
    }

    private fun ueberschreitungsdauer(nachZeitSortiert: List<MeasurementEntity>, schwelle: Double): Long {
        var summeMs = 0L
        for (i in 0 until nachZeitSortiert.size - 1) {
            if (nachZeitSortiert[i].levelDb > schwelle) {
                summeMs += nachZeitSortiert[i + 1].timestamp - nachZeitSortiert[i].timestamp
            }
        }
        return summeMs
    }
}
