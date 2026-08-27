package com.example.lrmprotokoll.report

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.lrmprotokoll.data.NoiseRecord
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

    private fun getDateString(records: List<NoiseRecord>): String {
        val timestamp = records.firstOrNull()?.timestamp ?: System.currentTimeMillis()
        return SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(timestamp))
    }

    fun generateDailyReport(records: List<NoiseRecord>, deviceName: String? = null): File {
        val dateStr = getDateString(records)
        val fileName = "Tagesbericht_$dateStr.txt"
        val file = File(context.getExternalFilesDir(null), fileName)
        val geraet = deviceName ?: "PCE-323"

        val content = StringBuilder()
        content.append("Lärmprotokoll - Tagesbericht für $dateStr\n")
        content.append("===========================================\n\n")
        unbestaetigteBewertungHinweis(records)?.let { content.append(it) }

        val maxDb = records.maxOfOrNull { it.calibratedDbA ?: it.dbValue } ?: 0.0
        val quietHourCount = records.count { it.isQuietHour }
        content.append("Gesamtzahl Ereignisse: ${records.size}\n")
        content.append("Spitzenpegel: ${String.format(Locale.getDefault(), "%.1f", maxDb)} dB\n")
        content.append("Ereignisse in Ruhezeiten: $quietHourCount\n\n")
        content.append("Einzelereignisse:\n")
        content.append("-------------------------------------------\n")

        records.forEach { record ->
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(record.timestamp))
            content.append("Zeit: $time\n")
            if (record.calibratedDbA != null) {
                content.append("Kalibrierter Pegel: ${String.format(Locale.getDefault(), "%.1f", record.calibratedDbA)} ${pegelEinheit(record.meterWeighting)} ($geraet)\n")
                content.append("Mikrofonpegel: ${String.format(Locale.getDefault(), "%.1f", record.dbValue)} dB\n")
            } else {
                content.append("Pegel: ${String.format(Locale.getDefault(), "%.1f", record.dbValue)} dB (Mikrofon)\n")
            }
            if (record.isQuietHour) {
                content.append("Hinweis: In Ruhezeit aufgetreten\n")
            }
            content.append("Amplitude: ${record.amplitude.toInt()}\n")
            content.append("Label: ${record.label ?: "Keines"}\n")
            content.append("KI Erkannt: ${record.detectedLabel ?: "Keines"}\n")
            content.append("-------------------------------------------\n")
        }

        file.writeText(content.toString())
        return file
    }

    /**
     * F12: Zeitraumbericht (Woche/Monat/Baulärm-Zusammenfassung).
     */
    fun generatePeriodReport(records: List<NoiseRecord>, title: String, deviceName: String? = null): File {
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
        val fileName = "Zeitraumbericht_${dateFormat.format(Date())}.txt"
        val file = File(context.getExternalFilesDir(null), fileName)
        val geraet = deviceName ?: "PCE-323"

        val content = StringBuilder()
        content.append("LÄRMPROTOKOLL - $title\n")
        content.append("Erstellt am: ${SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date())}\n")
        content.append("===========================================\n\n")
        unbestaetigteBewertungHinweis(records)?.let { content.append(it) }

        val count = records.size
        val maxLevel = records.maxOfOrNull { it.calibratedDbA ?: it.dbValue } ?: 0.0
        val avgLevel = if (records.isNotEmpty()) records.map { it.calibratedDbA ?: it.dbValue }.average() else 0.0
        val quietCount = records.count { it.isQuietHour }

        content.append("ZUSAMMENFASSUNG:\n")
        content.append("• Dokumentierte Lärmereignisse: $count\n")
        content.append("• Maximaler Spitzenpegel: ${String.format(Locale.getDefault(), "%.1f", maxLevel)} dB\n")
        content.append("• Durchschnittlicher Pegel: ${String.format(Locale.getDefault(), "%.1f", avgLevel)} dB\n")
        content.append("• Vorfälle in gesetzlichen Ruhezeiten: $quietCount\n\n")

        // Häufigste KI-Labels
        val labelStats = records.mapNotNull { it.detectedLabel ?: it.label }.groupingBy { it }.eachCount()
        if (labelStats.isNotEmpty()) {
            content.append("HÄUFIGSTE GERÄUSCHQUELLEN:\n")
            labelStats.entries.sortedByDescending { it.value }.take(5).forEach { (label, cnt) ->
                content.append("• $label: $cnt Vorfälle\n")
            }
            content.append("\n")
        }

        content.append("CHRONOLOGISCHE AUFLISTUNG:\n")
        content.append("-------------------------------------------\n")
        val fullFormat = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
        records.sortedBy { it.timestamp }.forEach { record ->
            val time = fullFormat.format(Date(record.timestamp))
            val pegelStr = if (record.calibratedDbA != null) {
                "${String.format(Locale.getDefault(), "%.1f", record.calibratedDbA)} ${pegelEinheit(record.meterWeighting)} ($geraet)"
            } else {
                "${String.format(Locale.getDefault(), "%.1f", record.dbValue)} dB (Mikrofon)"
            }
            val labelStr = record.label ?: record.detectedLabel ?: "Unbekannt"
            val ruheStr = if (record.isQuietHour) " [RUHEZEIT]" else ""
            content.append("$time | $pegelStr | $labelStr$ruheStr\n")
        }

        file.writeText(content.toString())
        return file
    }

    fun shareFile(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (file.extension == "wav") "audio/wav" else "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Teilen über...").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    fun createZipAndShare(records: List<NoiseRecord>, reportFile: File?) {
        val dateStr = getDateString(records)
        val zipFile = File(context.getExternalFilesDir(null), "Laermprotokoll_$dateStr.zip")

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
