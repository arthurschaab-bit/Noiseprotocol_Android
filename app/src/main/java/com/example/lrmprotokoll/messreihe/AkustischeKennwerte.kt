package com.example.lrmprotokoll.messreihe

import com.example.lrmprotokoll.data.MeasurementEntity
import com.example.lrmprotokoll.data.MinuteAggregateEntity
import java.time.Duration
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
     * Obergrenze fuer die Zeitgewichtung eines einzelnen Messwerts in [gewichteterLeq]
     * (Praefprotokoll-Befund 01 / Korrekturliste C-2). Ohne Deckel wuerde ein Verbindungsausfall
     * zwischen zwei Messwerten dem VOR dem Ausfall liegenden, laengst veralteten Pegel die
     * gesamte Ausfalldauer als Gewicht zuschreiben und ihn so kuenstlich dominieren lassen - 5 s
     * ist grosszuegig ueber jeder real vorkommenden Kadenz (Messgeraet ~515 ms, Mikrofon nach
     * Ausduennung hoechstens 1 s, siehe [MeasurementRecorder.mikrofonIntervall]) und trifft
     * trotzdem verlaesslich jede laengere Unterbrechung.
     */
    private val STANDARD_MAX_GEWICHTUNGSLUECKE: Duration = Duration.ofSeconds(5)

    /**
     * [ueberschreitungsSchwelleDb] ist optional, weil Kennwerte oft ohne eine konkrete Schwelle
     * gebraucht werden (z. B. fuer die Protokollansicht) - `null` liefert einfach 0 ms zurueck,
     * statt eine willkuerliche Schwelle anzunehmen.
     *
     * [maxGewichtungsluecke] siehe [STANDARD_MAX_GEWICHTUNGSLUECKE] - als Parameter statt
     * Konstante, damit Tests eine kleinere Grenze setzen koennen, ohne echte Sekunden warten zu
     * muessen.
     */
    fun berechne(
        messwerte: List<MeasurementEntity>,
        ueberschreitungsSchwelleDb: Double? = null,
        maxGewichtungsluecke: Duration = STANDARD_MAX_GEWICHTUNGSLUECKE,
    ): Kennwerte {
        if (messwerte.isEmpty()) return LEER

        val nachZeitSortiert = messwerte.sortedBy { it.timestamp }
        val pegel = nachZeitSortiert.map { it.levelDb }

        val leq = gewichteterLeq(nachZeitSortiert, maxGewichtungsluecke)
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

    /**
     * Schneller Pfad fuer Anzeigen, die nur LAeq und Max brauchen (PROMPT_M9A.md Aufgabe 1: das
     * Live-Cockpit zeigt beide Werte, aber keine Perzentile) - ohne die beiden WERT-Sortierungen
     * aus [berechne] (fuer die Perzentile), die diese Funktion bewusst vermeidet.
     * minDb/L10/L50/L90/Ueberschreitungsdauer bleiben bewusst `null` bzw. 0 statt sie halbherzig
     * mitzuberechnen - wer sie braucht (z. B. die Protokoll-Detailansicht), ruft weiterhin
     * [berechne] auf.
     *
     * Seit der Zeitgewichtung (Befund 01 / C-2) braucht [gewichteterLeq] die Werte chronologisch
     * sortiert - eine zusaetzliche ZEIT-Sortierung, aber eine billige (Room liefert `messwerte`
     * bei allen Aufrufern bereits `ORDER BY timestamp`; dies ist nur eine Absicherung gegen einen
     * kuenftigen Aufrufer, der das nicht mehr garantiert).
     */
    fun leqUndMax(
        messwerte: List<MeasurementEntity>,
        maxGewichtungsluecke: Duration = STANDARD_MAX_GEWICHTUNGSLUECKE,
    ): Kennwerte {
        if (messwerte.isEmpty()) return LEER
        val nachZeitSortiert = if (messwerte.size > 1) messwerte.sortedBy { it.timestamp } else messwerte
        var max = nachZeitSortiert[0].levelDb
        for (m in nachZeitSortiert) {
            if (m.levelDb > max) max = m.levelDb
        }
        return Kennwerte(
            leqDb = gewichteterLeq(nachZeitSortiert, maxGewichtungsluecke),
            maxDb = max,
            minDb = null,
            l10Db = null,
            l50Db = null,
            l90Db = null,
            ueberschreitungsdauerMs = 0L,
            sampleCount = nachZeitSortiert.size,
        )
    }

    /**
     * Energetischer Mittelwert, gewichtet mit der Zeit, die jeder Messwert repraesentiert - der
     * Zeitabstand zum jeweils NAECHSTEN Messwert, gekappt auf [maxLuecke]. Vorher (bis Befund 01
     * / C-2) wurde ungewichtet ueber die Sample-ANZAHL gemittelt - das ist nur dann ein echtes
     * Leq, wenn alle Werte exakt aequidistant eintreffen, was weder beim Messgeraet (~515 ms,
     * aber nie exakt) noch erst recht beim ausgeduennten Mikrofonwert (hoechstens 1/s) der Fall
     * ist. [ueberschreitungsdauer] rechnet in derselben Datei bereits mit echten Zeitabstaenden -
     * dieselbe Ueberlegung galt vorher nur nicht auch fuer den Leq selbst.
     *
     * Erwartet [nachZeitSortiert] chronologisch aufsteigend sortiert (Aufrufer-Pflicht, hier
     * nicht erneut geprueft - beide Aufrufer sortieren bereits selbst).
     *
     * Der LETZTE Messwert hat keine bekannte Nachfolgeluecke; er bekommt dieselbe Luecke wie der
     * Wert davor zugeschrieben (bei nur einem Messwert insgesamt: Gewicht spielt keine Rolle,
     * der Leq ist schlicht dieser eine Wert).
     */
    private fun gewichteterLeq(nachZeitSortiert: List<MeasurementEntity>, maxLuecke: Duration): Double {
        if (nachZeitSortiert.size == 1) return nachZeitSortiert[0].levelDb

        val maxLueckeMs = maxLuecke.toMillis()
        var gewichteteSumme = 0.0
        var gesamtgewicht = 0.0
        for (i in nachZeitSortiert.indices) {
            val luecke = if (i < nachZeitSortiert.size - 1) {
                nachZeitSortiert[i + 1].timestamp - nachZeitSortiert[i].timestamp
            } else {
                nachZeitSortiert[i].timestamp - nachZeitSortiert[i - 1].timestamp
            }.coerceAtLeast(0L)
            val gewicht = luecke.coerceAtMost(maxLueckeMs).toDouble()
            gewichteteSumme += gewicht * 10.0.pow(nachZeitSortiert[i].levelDb / 10.0)
            gesamtgewicht += gewicht
        }
        if (gesamtgewicht <= 0.0) {
            // Alle Luecken waren 0 (mehrere Messwerte mit identischem oder ruecklaeufigem
            // Zeitstempel) - Ruecksturz auf den einfachen energetischen Mittelwert statt einer
            // Division durch 0.
            return 10.0 * log10(nachZeitSortiert.sumOf { 10.0.pow(it.levelDb / 10.0) } / nachZeitSortiert.size)
        }
        return 10.0 * log10(gewichteteSumme / gesamtgewicht)
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
