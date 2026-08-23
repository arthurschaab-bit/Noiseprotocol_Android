package com.example.lrmprotokoll.drive

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Formatiert [AggregatZeile]n als CSV nach Plan Abschnitt 8.4.2: Semikolon als Trenner
 * (deutsches Excel-Gebietsschema), Dezimalkomma, UTF-8 mit BOM (damit Excel Umlaute korrekt
 * zeigt), CRLF-Zeilenenden.
 *
 * Abweichung vom Plan-Beispiel: Spaltenkoepfe heissen `LAeq_dB` / `LAFmax_dB` / `LAFmin_dB`,
 * nicht `LAeq_dBA` wie im Plan gezeigt. Weder das Mikrofon noch das PCE-323 liefert aktuell
 * einen gesicherten A-bewerteten Wert (siehe [com.example.lrmprotokoll.data.LevelSampleEntity]),
 * `dBA` in der Kopfzeile wuerde Genauigkeit vortaeuschen, die nicht besteht.
 */
object DriveCsv {

    private const val KOPFZEILE = "Zeit;Pegel_dB;LAeq_dB;LAFmax_dB;LAFmin_dB;Bewertung;Zeitbewertung;Messbereich;Samples;Quelle;Ereignis;Klassifikation;Notizen"
    private val ZEITFORMAT: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun schreibe(zeilen: List<AggregatZeile>, zone: ZoneId = ZoneId.systemDefault()): String {
        val sb = StringBuilder()
        sb.append('\uFEFF') // BOM
        sb.append(KOPFZEILE).append("\r\n")
        for (zeile in zeilen) {
            sb.append(formatiereZeile(zeile, zone)).append("\r\n")
        }
        return sb.toString()
    }

    private fun formatiereZeile(zeile: AggregatZeile, zone: ZoneId): String {
        val spalten = listOf(
            ZEITFORMAT.format(zeile.fensterStart.atZone(zone)),
            formatiereDezimalkomma(zeile.pegelDb),
            formatiereDezimalkomma(zeile.laeqDb),
            formatiereDezimalkomma(zeile.lafMaxDb),
            formatiereDezimalkomma(zeile.lafMinDb),
            zeile.bewertung.orEmpty(),
            zeile.zeitbewertung.orEmpty(),
            zeile.messbereich.orEmpty(),
            zeile.samples.toString(),
            zeile.quelle,
            if (zeile.ereignis) "JA" else "",
            zeile.klassifikation.orEmpty(),
            zeile.notes.orEmpty(),
        )
        return spalten.joinToString(";")
    }

    private fun formatiereDezimalkomma(wert: Double?): String {
        if (wert == null) return ""
        return String.format(Locale.GERMANY, "%.1f", wert)
    }
}
