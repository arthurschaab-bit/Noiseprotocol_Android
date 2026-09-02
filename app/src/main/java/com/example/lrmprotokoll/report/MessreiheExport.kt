package com.example.lrmprotokoll.report

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import com.example.lrmprotokoll.data.DokumentationsFotoEntity
import com.example.lrmprotokoll.data.FotoKategorie
import com.example.lrmprotokoll.data.MeasurementEntity
import com.example.lrmprotokoll.data.MinuteAggregateEntity
import com.example.lrmprotokoll.data.SessionEntity
import com.example.lrmprotokoll.messreihe.AkustischeKennwerte
import com.example.lrmprotokoll.messreihe.Ausfallband
import com.example.lrmprotokoll.messreihe.MessreiheCsv
import com.example.lrmprotokoll.report.pdf.BerichtLayout
import com.example.lrmprotokoll.report.pdf.BerichtSeiten
import com.example.lrmprotokoll.report.pdf.Seitenlauf
import java.io.File
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
        val file = File(BerichtDatei.ordner(context), dateiname(session, "csv"))
        file.writeText(inhalt, Charsets.UTF_8)
        return file
    }

    fun exportierePdf(
        session: SessionEntity,
        kennwerte: AkustischeKennwerte.Kennwerte,
        ausfallbaender: List<Ausfallband>,
        fotos: List<DokumentationsFotoEntity> = emptyList(),
    ): File {
        val formatierer = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        val titel = "Session ${formatierer.format(Date(session.startedAt))}"

        // Zwei Durchlaeufe ueber denselben Aufbau: zaehlen, dann zeichnen - siehe Seitenlauf.
        // Frueher lief dieser Bericht auf genau einer Seite und liess alles, was darunter kam,
        // kommentarlos ueber den Blattrand hinauslaufen. Bei einer Session mit vielen Ausfaellen
        // fehlten damit genau die Eintraege, wegen derer man den Bericht zieht.
        val aufbau: (Seitenlauf, () -> Canvas?) -> Unit = { lauf, canvasGeber ->
            val x = Seitenlauf.RAND_LINKS
            val titelPaint = BerichtLayout.paint(BerichtLayout.COLOR_PRIMARY, textSize = 16f, fett = true)
            val kopfPaint = BerichtLayout.paint(BerichtLayout.COLOR_PRIMARY, textSize = 13f, fett = true)
            val textPaint = BerichtLayout.paint(textSize = 11f)

            var y = lauf.platziere(30f)
            canvasGeber()?.drawText("Lärmprotokoll – $titel", x, y + 16f, titelPaint)

            y = lauf.platziere(20f)
            canvasGeber()?.drawText("Gerät: ${session.deviceName} (${session.deviceAddress})", x, y + 12f, textPaint)
            lauf.abstand(12f)

            y = lauf.platziere(24f)
            canvasGeber()?.drawText("Kennwerte", x, y + 14f, kopfPaint)

            val kennwertZeilen = listOfNotNull(
                kennwerte.leqDb?.let { "LAeq: ${formatiereDb(it)} dB" },
                kennwerte.maxDb?.let { "Max: ${formatiereDb(it)} dB" },
                kennwerte.minDb?.let { "Min: ${formatiereDb(it)} dB" },
                kennwerte.l10Db?.let { "L10: ${formatiereDb(it)} dB" },
                kennwerte.l50Db?.let { "L50: ${formatiereDb(it)} dB" },
                kennwerte.l90Db?.let { "L90: ${formatiereDb(it)} dB" },
            ).ifEmpty {
                // Nach der Umstellung auf E1 kann eine Session auch ein reiner Mikrofonlauf ohne
                // Messwerte sein. Dann ist "keine Kennwerte" die Aussage - nicht eine Liste
                // von Strichen, die wie ein Messergebnis aussieht.
                listOf("Für diese Session liegen keine Messwerte vor.")
            }
            kennwertZeilen.forEach { zeile ->
                val zy = lauf.platziere(18f)
                canvasGeber()?.drawText(zeile, x, zy + 12f, textPaint)
            }
            val zy = lauf.platziere(18f)
            canvasGeber()?.drawText("${kennwerte.sampleCount} Messwerte", x, zy + 12f, textPaint)
            lauf.abstand(12f)

            var ay = lauf.platziere(24f)
            canvasGeber()?.drawText("Ausfälle (${ausfallbaender.size})", x, ay + 14f, kopfPaint)

            if (ausfallbaender.isEmpty()) {
                ay = lauf.platziere(18f)
                canvasGeber()?.drawText("Keine Verbindungsausfälle während dieser Session.", x, ay + 12f, textPaint)
            } else {
                ausfallbaender.forEach { band ->
                    val ende = band.bis?.let { formatierer.format(Date(it)) } ?: "andauernd"
                    val by = lauf.platziere(18f)
                    canvasGeber()?.drawText("${formatierer.format(Date(band.von))} – $ende", x, by + 12f, textPaint)
                }
            }
        }

        val mitFotos: (Seitenlauf, () -> Canvas?) -> Unit = { lauf, canvasGeber ->
            aufbau(lauf, canvasGeber)
            zeichneFotoanhang(lauf, canvasGeber, fotos, formatierer)
        }

        val datei = File(BerichtDatei.ordner(context), dateiname(session, "pdf"))
        BerichtSeiten.schreibe(datei, abschnitt = "Sessionbericht", fussHinweis = titel, aufbau = mitFotos)
        return datei
    }

    /**
     * Fotoanhang am Ende des Sessionberichts (M11 Etappe A).
     *
     * Ein Foto, dessen Datei nicht mehr existiert, erzeugt einen Textplatzhalter statt eines
     * Absturzes: Ein Bericht, der wegen eines fehlenden Bildes gar nicht erst entsteht, ist der
     * schlimmere Fehler.
     */
    private fun zeichneFotoanhang(
        lauf: Seitenlauf,
        canvasGeber: () -> Canvas?,
        fotos: List<DokumentationsFotoEntity>,
        formatierer: SimpleDateFormat,
    ) {
        if (fotos.isEmpty()) return

        val x = Seitenlauf.RAND_LINKS
        val kopfPaint = BerichtLayout.paint(BerichtLayout.COLOR_PRIMARY, textSize = 13f, fett = true)
        val textPaint = BerichtLayout.paint(textSize = 11f)
        val kleinPaint = BerichtLayout.paint(BerichtLayout.COLOR_TEXT_MUTED, textSize = 9f)

        lauf.abstand(14f)
        val ky = lauf.platziere(24f)
        canvasGeber()?.drawText("Fotodokumentation (${fotos.size})", x, ky + 14f, kopfPaint)

        fotos.forEach { foto ->
            val kategorie = FotoKategorie.vonName(foto.kategorie).anzeigename
            val zeitpunkt = formatierer.format(Date(foto.aufgenommenAm))
            val datei = File(foto.dateiPfad)

            val bild = if (datei.exists()) {
                runCatching { BitmapFactory.decodeFile(foto.dateiPfad) }.getOrNull()
            } else {
                null
            }

            if (bild == null) {
                val zy = lauf.platziere(18f)
                canvasGeber()?.drawText("[Foto nicht mehr verfügbar: ${datei.name}]", x, zy + 12f, textPaint)
                return@forEach
            }

            val breite = minOf(240f, Seitenlauf.INHALT_BREITE)
            val hoehe = breite * bild.height / bild.width.coerceAtLeast(1)
            // Bild und Bildunterschrift als EIN Block reservieren, damit die Unterschrift nicht
            // ohne ihr Bild auf der Folgeseite landet.
            val by = lauf.platziere(hoehe + 30f)
            canvasGeber()?.let { c ->
                c.drawBitmap(bild, null, RectF(x, by, x + breite, by + hoehe), null)
                c.drawText("$kategorie · $zeitpunkt", x, by + hoehe + 12f, kleinPaint)
                foto.notiz?.let { notiz -> c.drawText(notiz, x, by + hoehe + 24f, kleinPaint) }
            }
            bild.recycle()
        }
    }

    private fun formatiereDb(wert: Double) = String.format(Locale.getDefault(), "%.1f", wert)

    fun teilen(file: File) = BerichtDatei.teile(context, file)
}
