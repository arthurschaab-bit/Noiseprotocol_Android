package com.example.lrmprotokoll.report

import android.content.Context
import com.example.lrmprotokoll.data.NoiseRecord
import com.example.lrmprotokoll.report.pdf.TagesberichtPdf
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Einheit fuer einen kalibrierten Pegelwert - "dBA"/"dBC" nur, wenn die Frequenzbewertung fuer
 * GENAU diesen Datensatz bestaetigt war ([NoiseRecord.meterWeighting] ist dann "A"/"C"), sonst
 * "dB". Nicht pauschal "dBA" annehmen, nur weil ueberhaupt ein kalibrierter Wert vorliegt -
 * [calibratedDbA][NoiseRecord.calibratedDbA] wird unabhaengig davon geschrieben, ob die
 * Bewertung zum Zeitpunkt DIESES Datensatzes schon bestaetigt war (siehe
 * MeasurementRecorder.onFrame). Als eigenstaendige Funktion pruefbar ohne den Context, den
 * ReportManager selbst braucht.
 */
internal fun pegelEinheit(meterWeighting: String?): String = when (meterWeighting) {
    "A" -> "dBA"
    "C" -> "dBC"
    else -> "dB"
}

/**
 * PROMPT_M9_UX.md Aufgabe 9: ehrlicher Kopf-Hinweis, solange die Frequenzbewertung fuer
 * mindestens einen kalibrierten Wert im Bericht unbestaetigt war ([NoiseRecord.meterWeighting]
 * `null` trotz gesetztem [NoiseRecord.calibratedDbA] - das passiert bei Datensaetzen, die
 * aufgezeichnet wurden, bevor [com.example.lrmprotokoll.meter.ble.Pce323Profile.MODE_ASSUMPTION_CONFIRMED]
 * auf den Geraetetest-Beweis umgestellt wurde). [pegelEinheit] schreibt fuer diese Datensaetze
 * bereits korrekt "dB" statt "dBA" - dieser Satz erklaert im Bericht selbst, warum, statt den
 * Leser raten zu lassen. Liefert `null`, wenn kein solcher Datensatz im Bericht vorkommt.
 */
internal fun unbestaetigteBewertungHinweis(records: List<NoiseRecord>): String? {
    val betroffen = records.count { it.calibratedDbA != null && it.meterWeighting == null }
    if (betroffen == 0) return null
    return "Hinweis: Bei $betroffen von ${records.size} Ereignissen war die Frequenzbewertung " +
        "(A- oder C-Bewertung) des Messgeräts zum Aufnahmezeitpunkt nicht bestätigt - dort steht " +
        "bewusst die reine Einheit \"dB\" ohne A- oder C-Kennzeichnung, um keine Genauigkeit " +
        "vorzutäuschen, die nicht belegt war.\n\n"
}

class ReportManager(private val context: Context) {

    /**
     * Tagesbericht als PDF. Frueher eine `.txt`-Datei - umgestellt im Zuge der
     * Berichts-Konsolidierung, damit alle Berichte dasselbe Format haben und der Tagesbericht
     * gegenueber Behoerden dieselbe Belastbarkeit hat wie der Sessionbericht.
     *
     * Der Inhalt entsteht in [ermittleTagesbericht] (rein, getestet), die Ausgabe in
     * [TagesberichtPdf]. Diese Methode verklammert nur beides und bestimmt den Dateinamen.
     */
    fun generateDailyReport(records: List<NoiseRecord>, deviceName: String? = null): File {
        val daten = ermittleTagesbericht(records, deviceName)
        val datei = File(BerichtDatei.ordner(context), "Tagesbericht_${daten.datum}.pdf")
        return TagesberichtPdf.schreibe(daten, datei)
    }

    fun shareFile(file: File) = BerichtDatei.teile(context, file)

    fun createZipAndShare(records: List<NoiseRecord>, reportFile: File?) {
        val dateStr = ermittleTagesbericht(records).datum
        val zipFile = File(BerichtDatei.ordner(context), "Laermprotokoll_$dateStr.zip")

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            records.forEach { record ->
                val audioFile = File(record.filePath)
                if (audioFile.exists()) {
                    zos.putNextEntry(ZipEntry(audioFile.name))
                    zos.write(audioFile.readBytes())
                    zos.closeEntry()
                }
            }
            reportFile?.let {
                zos.putNextEntry(ZipEntry(it.name))
                zos.write(it.readBytes())
                zos.closeEntry()
            }
        }

        shareFile(zipFile)
    }
}
