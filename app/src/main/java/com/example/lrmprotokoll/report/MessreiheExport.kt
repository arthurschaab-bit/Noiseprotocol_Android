package com.example.lrmprotokoll.report

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.example.lrmprotokoll.data.MeasurementEntity
import com.example.lrmprotokoll.data.MinuteAggregateEntity
import com.example.lrmprotokoll.data.SessionEntity
import com.example.lrmprotokoll.messreihe.AkustischeKennwerte
import com.example.lrmprotokoll.messreihe.Ausfallband
import com.example.lrmprotokoll.messreihe.MessreiheCsv
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Export der Messreihe einer Session (Plan Abschnitt 9: "Export CSV/PDF"). Folgt demselben
 * Datei-/Teilen-Muster wie [ReportManager] (Tagesbericht, legacy Audio-Erkennung):
 * `context.getExternalFilesDir(null)` (App-eigener externer Speicher, per [file_paths.xml] fuer
 * den [FileProvider] freigegeben) statt eines geteilten `file://`-Pfads, Teilen ueber
 * `ACTION_SEND` + [FileProvider].
 *
 * Das PDF ist bewusst ein reiner Textbericht ohne Grafik/Diagramm - kein neuer
 * PDF-Bibliotheks-Dependency, nur `android.graphics.pdf.PdfDocument` aus dem SDK, passend zum
 * minimalen Abhaengigkeits-Stil des Projekts (siehe ZIP-Export in [ReportManager], der ebenfalls
 * nur `java.util.zip` statt einer Bibliothek nutzt).
 */
class MessreiheExport(private val context: Context) {

    private fun dateiname(session: SessionEntity, endung: String): String {
        val datum = SimpleDateFormat("dd.MM.yyyy_HHmm", Locale.getDefault()).format(Date(session.startedAt))
        return "Session_$datum.$endung"
    }

    /** [messwerte] wird bevorzugt (volle Genauigkeit), [aggregate] nur als Rueckfall fuer
     * Sessions, deren Rohwerte der Retention-Job (Plan 13.2) bereits verdichtet hat. */
    fun exportiereCsv(
        session: SessionEntity,
        messwerte: List<MeasurementEntity>,
        aggregate: List<MinuteAggregateEntity>,
    ): File {
        val inhalt = if (messwerte.isNotEmpty()) {
            MessreiheCsv.ausMesswerten(messwerte)
        } else {
            MessreiheCsv.ausAggregaten(aggregate)
        }
        val file = File(context.getExternalFilesDir(null), dateiname(session, "csv"))
        file.writeText(inhalt, Charsets.UTF_8)
        return file
    }

    fun exportierePdf(
        session: SessionEntity,
        kennwerte: AkustischeKennwerte.Kennwerte,
        ausfallbaender: List<Ausfallband>,
    ): File {
        val dokument = PdfDocument()
        // A4 bei 72 dpi (595 x 842 pt) - eine Seite reicht fuer Kennwerte + Ausfallliste einer
        // einzelnen Session; bei sehr vielen Ausfaellen laeuft der Text einfach ueber den
        // unteren Rand hinaus statt eine zweite Seite anzulegen - fuer einen Kennwerte-Bericht
        // kein praktisch relevanter Fall.
        val seite = dokument.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val canvas = seite.canvas
        val titel = Paint().apply { textSize = 16f; isFakeBoldText = true }
        val text = Paint().apply { textSize = 12f }
        val formatierer = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

        var y = 48f
        fun zeile(inhalt: String, paint: Paint = text, abstand: Float = 18f) {
            canvas.drawText(inhalt, 40f, y, paint)
            y += abstand
        }

        zeile("Lärmprotokoll – Session ${formatierer.format(Date(session.startedAt))}", titel, 28f)
        zeile("Gerät: ${session.deviceName} (${session.deviceAddress})")
        y += 12f

        zeile("Kennwerte", titel, 24f)
        listOfNotNull(
            kennwerte.leqDb?.let { "LAeq: ${formatiereDb(it)} dB" },
            kennwerte.maxDb?.let { "Max: ${formatiereDb(it)} dB" },
            kennwerte.minDb?.let { "Min: ${formatiereDb(it)} dB" },
            kennwerte.l10Db?.let { "L10: ${formatiereDb(it)} dB" },
            kennwerte.l50Db?.let { "L50: ${formatiereDb(it)} dB" },
            kennwerte.l90Db?.let { "L90: ${formatiereDb(it)} dB" },
        ).forEach { zeile(it) }
        zeile("${kennwerte.sampleCount} Messwerte")
        y += 12f

        zeile("Ausfälle (${ausfallbaender.size})", titel, 24f)
        if (ausfallbaender.isEmpty()) {
            zeile("Keine Verbindungsausfälle während dieser Session.")
        } else {
            ausfallbaender.forEach { band ->
                val ende = band.bis?.let { formatierer.format(Date(it)) } ?: "andauernd"
                zeile("${formatierer.format(Date(band.von))} – $ende")
            }
        }

        dokument.finishPage(seite)
        val file = File(context.getExternalFilesDir(null), dateiname(session, "pdf"))
        FileOutputStream(file).use { dokument.writeTo(it) }
        dokument.close()
        return file
    }

    private fun formatiereDb(wert: Double) = String.format(Locale.getDefault(), "%.1f", wert)

    fun teilen(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = when (file.extension) {
                "csv" -> "text/csv"
                "pdf" -> "application/pdf"
                else -> "application/octet-stream"
            }
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Teilen über…"))
    }
}
