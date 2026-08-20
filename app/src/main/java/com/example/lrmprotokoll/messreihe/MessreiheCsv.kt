package com.example.lrmprotokoll.messreihe

import com.example.lrmprotokoll.data.MeasurementEntity
import com.example.lrmprotokoll.data.MinuteAggregateEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Export der Messreihe einer Session als CSV (Plan Abschnitt 9). Folgt derselben Konvention wie
 * [com.example.lrmprotokoll.drive.DriveCsv] (M7b): Semikolon-Trenner, Dezimalkomma, UTF-8 mit BOM,
 * CRLF - dieselbe Abweichung vom Plan-Beispiel gilt hier ebenso: `_dB`, nicht `_dBA`, weil weder
 * Mikrofon noch PCE-323 aktuell einen gesicherten A-bewerteten Wert liefern.
 *
 * Zwei getrennte Funktionen statt einer gemeinsamen: [ausMesswerten] exportiert die vollen
 * Rohwerte (eine Zeile je Messwert), [ausAggregaten] die vom Retention-Job (Plan 13.2) bereits
 * verdichteten Minutenaggregate einer älteren Session - unterschiedliche Spaltenköpfe, weil es
 * fachlich unterschiedliche Daten sind (kein gemeinsamer Nenner ohne Informationsverlust).
 */
object MessreiheCsv {

    private const val KOPFZEILE_MESSWERTE = "Zeit;Pegel_dB;Bewertung;Zeitbewertung;Bereich;Flags"
    private const val KOPFZEILE_AGGREGATE = "Minute;LAeq_dB;Max_dB;Min_dB;Samples;Bewertung;Zeitbewertung;Bereich"
    private val ZEITFORMAT: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun ausMesswerten(messwerte: List<MeasurementEntity>, zone: ZoneId = ZoneId.systemDefault()): String {
        val sb = StringBuilder()
        sb.append('\uFEFF') // BOM
        sb.append(KOPFZEILE_MESSWERTE).append("\r\n")
        for (messwert in messwerte.sortedBy { it.timestamp }) {
            val spalten = listOf(
                ZEITFORMAT.format(Instant.ofEpochMilli(messwert.timestamp).atZone(zone)),
                formatiereDezimalkomma(messwert.levelDb),
                messwert.weighting.orEmpty(),
                messwert.timeWeighting.orEmpty(),
                messwert.range.orEmpty(),
                messwert.flags.toString(),
            )
            sb.append(spalten.joinToString(";")).append("\r\n")
        }
        return sb.toString()
    }

    fun ausAggregaten(aggregate: List<MinuteAggregateEntity>, zone: ZoneId = ZoneId.systemDefault()): String {
        val sb = StringBuilder()
        sb.append('\uFEFF') // BOM
        sb.append(KOPFZEILE_AGGREGATE).append("\r\n")
        for (aggregat in aggregate.sortedBy { it.minuteStart }) {
            val spalten = listOf(
                ZEITFORMAT.format(Instant.ofEpochMilli(aggregat.minuteStart).atZone(zone)),
                formatiereDezimalkomma(aggregat.leqDb),
                formatiereDezimalkomma(aggregat.maxDb),
                formatiereDezimalkomma(aggregat.minDb),
                aggregat.sampleCount.toString(),
                aggregat.weighting.orEmpty(),
                aggregat.timeWeighting.orEmpty(),
                aggregat.range.orEmpty(),
            )
            sb.append(spalten.joinToString(";")).append("\r\n")
        }
        return sb.toString()
    }

    private fun formatiereDezimalkomma(wert: Double): String = String.format(Locale.GERMANY, "%.1f", wert)
}
