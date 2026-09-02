package com.example.lrmprotokoll.report

import com.example.lrmprotokoll.data.NoiseRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Der Inhalt des Tagesberichts als reines Datenmodell - unabhaengig davon, ob er als Text oder
 * als PDF ausgegeben wird.
 *
 * Diese Trennung ist nicht Selbstzweck: Der Tagesbericht war bisher eine `.txt`-Datei, und seine
 * Tests haben den fertigen Text gelesen und darin nach Zeichenketten gesucht - darunter die
 * fachlich wichtigste Pruefung des Berichts ueberhaupt, dass "dBA" nur dort steht, wo die
 * Frequenzbewertung fuer genau dieses Ereignis bestaetigt war ([pegelEinheit]). Mit der
 * Umstellung auf PDF waeren diese Tests ersatzlos verloren gewesen, weil `PdfDocument()` unter
 * Robolectric bei jedem `startPage()` wirft (siehe KDoc von [MessreiheExportTest]) und ein PDF
 * sich nicht als Text zurueckliesen laesst.
 *
 * Mit dem Modell dazwischen pruefen dieselben Tests dieselbe Zusage weiterhin - und sogar
 * genauer, weil sie auf einzelne Felder zugreifen statt auf Textfunde. Dasselbe Muster, das
 * [PeriodenBericht] fuer den Zeitraumbericht schon verfolgt.
 */
data class TagesberichtZeile(
    val uhrzeit: String,
    /** Fertig formatierter Pegel inklusive Einheit und Quelle, z.B. "70,5 dBA (PCE-323)". */
    val pegel: String,
    val inRuhezeit: Boolean,
    val label: String?,
    val kiLabel: String?,
    val amplitude: Int,
)

data class TagesberichtDaten(
    val datum: String,
    val geraet: String,
    val anzahlEreignisse: Int,
    val spitzenpegel: Double,
    val anzahlRuhezeit: Int,
    /**
     * Der Hinweis aus [unbestaetigteBewertungHinweis], falls mindestens ein Ereignis einen
     * kalibrierten Wert ohne bestaetigte Frequenzbewertung hat - sonst `null`. Bewusst Teil des
     * Modells: Er ist eine inhaltliche Aussage des Berichts, keine Formatierung.
     */
    val bewertungsHinweis: String?,
    val zeilen: List<TagesberichtZeile>,
)

/**
 * Baut den Tagesbericht aus [records]. Reine Funktion: keine Datei, kein Context, kein Canvas.
 *
 * [geraeteName] `null` faellt auf "PCE-323" zurueck - wie bisher im ReportManager.
 */
fun ermittleTagesbericht(records: List<NoiseRecord>, geraeteName: String? = null): TagesberichtDaten {
    val geraet = geraeteName ?: "PCE-323"
    val datumsFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
    val zeitFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    val datum = datumsFormat.format(Date(records.firstOrNull()?.timestamp ?: System.currentTimeMillis()))

    val zeilen = records.map { record ->
        // Die Fallunterscheidung ist die inhaltliche Zusage des Berichts: Ein kalibrierter Wert
        // wird mit Geraet und der fuer DIESEN Datensatz belegten Einheit ausgewiesen, ein reiner
        // Mikrofonwert ausdruecklich als solcher - siehe pegelEinheit().
        val pegelText = if (record.calibratedDbA != null) {
            "${formatiere(record.calibratedDbA)} ${pegelEinheit(record.meterWeighting)} ($geraet)"
        } else {
            "${formatiere(record.dbValue)} dB (Mikrofon)"
        }
        TagesberichtZeile(
            uhrzeit = zeitFormat.format(Date(record.timestamp)),
            pegel = pegelText,
            inRuhezeit = record.isQuietHour,
            label = record.label,
            kiLabel = record.detectedLabel,
            amplitude = record.amplitude.toInt(),
        )
    }

    return TagesberichtDaten(
        datum = datum,
        geraet = geraet,
        anzahlEreignisse = records.size,
        spitzenpegel = records.maxOfOrNull { it.calibratedDbA ?: it.dbValue } ?: 0.0,
        anzahlRuhezeit = records.count { it.isQuietHour },
        bewertungsHinweis = unbestaetigteBewertungHinweis(records)?.trim()?.ifBlank { null },
        zeilen = zeilen,
    )
}

internal fun formatiere(wert: Double): String = String.format(Locale.getDefault(), "%.1f", wert)
