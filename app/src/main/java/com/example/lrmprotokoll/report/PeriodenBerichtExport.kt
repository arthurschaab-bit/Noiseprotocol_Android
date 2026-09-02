package com.example.lrmprotokoll.report

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfDocument
import com.example.lrmprotokoll.data.NoiseRecord
import com.example.lrmprotokoll.messreihe.Ausfallband
import com.example.lrmprotokoll.messreihe.ChartSpalte
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

/**
 * PDF-Export eines [PeriodenBericht] (F12, PROMPT_M10_FUNKTIONEN.md: "Wochen- und Monatsbericht
 * mit Diagramm"). Folgt demselben Muster wie [MessreiheExport.exportierePdf]
 * (`android.graphics.pdf.PdfDocument`, kein PDF-Bibliotheks-Dependency,
 * `getExternalFilesDir`/[FileProvider]-Teilen) - hier zusaetzlich ein gezeichnetes Diagramm, dessen
 * Zeichenlogik von [com.example.lrmprotokoll.ui.PegelverlaufChart] auf `android.graphics.Canvas`
 * portiert ist ([zeichnePegelverlaufChart] unten). Compose' `DrawScope` und `android.graphics.Canvas`
 * sind unabhaengige APIs - eine gemeinsame Zeichenroutine fuer beide gibt es nicht. Zoom/Pan und
 * der Live-Puls-Punkt des interaktiven Charts entfallen bewusst, weil ein PDF statisch ist.
 *
 * Bewusst eine einzelne Seite (wie [MessreiheExport.exportierePdf]): Ausfaelle und Ereignisse
 * werden nur bis zum unteren Seitenrand aufgelistet, der Rest als "… und N weitere" zusammengefasst,
 * statt eine unbegrenzte Mehrseiten-Paginierung zu bauen - fuer einen Wochen-/Monatsbericht mit
 * ueblicherweise wenigen Ausfaellen/markierten Ereignissen ausreichend. Unter Robolectric nicht
 * testbar (PdfDocument braucht einen echten Renderer, siehe PROMPT_M10_FUNKTIONEN.md F12) - nur am
 * Geraet pruefbar, ebenso wie [MessreiheExport.exportierePdf].
 */
class PeriodenBerichtExport(private val context: Context) {

    fun exportierePdf(bericht: PeriodenBericht, titel: String): File {
        val dokument = PdfDocument()
        // A4 bei 72 dpi (595 x 842 pt), wie MessreiheExport.exportierePdf.
        val seite = dokument.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val canvas = seite.canvas

        val titelPaint = Paint().apply { textSize = 16f; isFakeBoldText = true; isAntiAlias = true }
        val headerPaint = Paint().apply { textSize = 13f; isFakeBoldText = true; isAntiAlias = true }
        val textPaint = Paint().apply { textSize = 11f; isAntiAlias = true }
        val formatierer = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        val kennwerte = bericht.kennwerte

        val linksrand = 40f
        val rechtsrand = 555f
        val unterrand = 800f
        var y = 48f

        fun zeile(inhalt: String, paint: Paint = textPaint, abstand: Float = 16f): Boolean {
            if (y > unterrand) return false
            canvas.drawText(inhalt, linksrand, y, paint)
            y += abstand
            return true
        }

        zeile(titel, titelPaint, 26f)
        zeile("Zeitraum: ${formatierer.format(Date(bericht.von))} – ${formatierer.format(Date(bericht.bis))}")
        zeile("${bericht.sessionCount} Session(en), ${kennwerte.sampleCount} Messwerte")
        y += 10f

        zeile("Kennwerte", headerPaint, 22f)
        listOfNotNull(
            kennwerte.leqDb?.let { "LAeq: ${formatiereDb(it)} dB" },
            kennwerte.maxDb?.let { "Max: ${formatiereDb(it)} dB" },
            kennwerte.minDb?.let { "Min: ${formatiereDb(it)} dB" },
            kennwerte.l10Db?.let { "L10: ${formatiereDb(it)} dB" },
            kennwerte.l50Db?.let { "L50: ${formatiereDb(it)} dB" },
            kennwerte.l90Db?.let { "L90: ${formatiereDb(it)} dB" },
        ).forEach { zeile(it) }
        y += 10f

        zeile("Pegelverlauf", headerPaint, 22f)
        val chartTop = y
        val chartHeight = 220f
        zeichnePegelverlaufChart(
            canvas = canvas,
            spalten = bericht.chartSpalten,
            ausfallbaender = bericht.ausfallbaender,
            events = bericht.events,
            von = bericht.von,
            bis = bericht.bis,
            laeqDb = kennwerte.leqDb,
            left = linksrand,
            top = chartTop,
            right = rechtsrand,
            bottom = chartTop + chartHeight,
        )
        y = chartTop + chartHeight + 20f

        zeile("Ausfälle (${bericht.ausfallbaender.size})", headerPaint, 22f)
        if (bericht.ausfallbaender.isEmpty()) {
            zeile("Keine Verbindungsausfälle in diesem Zeitraum.")
        } else {
            var angezeigt = 0
            for (band in bericht.ausfallbaender) {
                val ende = band.bis?.let { formatierer.format(Date(it)) } ?: "andauernd"
                if (!zeile("${formatierer.format(Date(band.von))} – $ende")) break
                angezeigt++
            }
            if (angezeigt < bericht.ausfallbaender.size) {
                zeile("… und ${bericht.ausfallbaender.size - angezeigt} weitere")
            }
        }
        y += 10f

        zeile("Ereignisse (${bericht.events.size})", headerPaint, 22f)
        if (bericht.events.isEmpty()) {
            zeile("Keine markierten Ereignisse in diesem Zeitraum.")
        } else {
            var angezeigt = 0
            for (event in bericht.events) {
                val pegel = event.calibratedDbA ?: event.dbValue
                val beschriftung = event.label ?: event.detectedLabel ?: "Ereignis"
                if (!zeile("${formatierer.format(Date(event.timestamp))} – $beschriftung (${formatiereDb(pegel)} dB)")) break
                angezeigt++
            }
            if (angezeigt < bericht.events.size) {
                zeile("… und ${bericht.events.size - angezeigt} weitere")
            }
        }

        dokument.finishPage(seite)
        val dateiFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
        val dateiname = "Zeitraumbericht_${dateiFormat.format(Date(bericht.von))}-${dateiFormat.format(Date(bericht.bis))}.pdf"
        val file = File(context.getExternalFilesDir(null), dateiname)
        FileOutputStream(file).use { dokument.writeTo(it) }
        dokument.close()
        return file
    }

    private fun formatiereDb(wert: Double) = String.format(Locale.getDefault(), "%.1f", wert)

    fun teilen(file: File) = BerichtDatei.teile(context, file)
}

/**
 * Auf `android.graphics.Canvas` portierte Teilmenge der Zeichenlogik aus
 * [com.example.lrmprotokoll.ui.PegelverlaufChart]: Y-Achsen-Gitter samt Beschriftung, gestrichelte
 * LAeq-Referenzlinie, Ausfallbaender als halbtransparente Flaechen, die Min/Max-Flaeche samt
 * Mittelwertlinie (segmentiert bei Datenluecken, exakt wie im Original) und Ereignis-Pins. Die
 * X-Achse zeigt Datum statt Uhrzeit (`dd.MM.` statt `HH:mm`), weil ein Zeitraumbericht anders als
 * eine einzelne Session mehrere Tage umfassen kann. Ohne Zoom/Pan (ein PDF ist statisch, das
 * Original bietet das nur fuer die interaktive Anzeige) und ohne den Live-Puls-Punkt (nur fuer die
 * laufende Aufzeichnung relevant, ein Periodenbericht schaut immer zurueck).
 */
private fun zeichnePegelverlaufChart(
    canvas: Canvas,
    spalten: List<ChartSpalte>,
    ausfallbaender: List<Ausfallband>,
    events: List<NoiseRecord>,
    von: Long,
    bis: Long,
    laeqDb: Double?,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
) {
    val textPaint = Paint().apply { textSize = 9f; color = Color.DKGRAY; isAntiAlias = true }
    if (spalten.isEmpty()) {
        canvas.drawText("Keine Messwerte für den Pegelverlauf.", left, (top + bottom) / 2f, textPaint)
        return
    }

    val yAxisWidth = 34f
    val xAxisHeight = 16f
    val plotLeft = left + yAxisWidth
    val plotRight = right
    val plotTop = top
    val plotBottom = bottom - xAxisHeight
    val plotWidth = (plotRight - plotLeft).coerceAtLeast(1f)
    val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)

    val rawMinDb = spalten.minOf { it.minDb }
    val rawMaxDb = spalten.maxOf { it.maxDb }
    val minScaleDb = floor((rawMinDb - 2.0) / 10.0) * 10.0
    val maxScaleDb = ceil((maxOf(rawMaxDb, laeqDb ?: 0.0) + 4.0) / 10.0) * 10.0
    val dbSpanne = (maxScaleDb - minScaleDb).coerceAtLeast(10.0)
    val gesamtSekunden = ((bis - von) / 1000).coerceAtLeast(1)

    fun x(sekunden: Long): Float = plotLeft + (sekunden.toFloat() / gesamtSekunden) * plotWidth
    fun y(db: Double): Float {
        val ratio = ((db - minScaleDb) / dbSpanne).coerceIn(0.0, 1.0)
        return (plotBottom - ratio * plotHeight).toFloat()
    }

    val gridPaint = Paint().apply { color = Color.LTGRAY; strokeWidth = 0.75f }
    val rahmenPaint = Paint().apply { color = Color.GRAY; style = Paint.Style.STROKE; strokeWidth = 1f }
    val curveLinePaint = Paint().apply {
        color = Color.rgb(21, 101, 192); strokeWidth = 1.6f; style = Paint.Style.STROKE; isAntiAlias = true
    }
    val curveDotPaint = Paint().apply { color = Color.rgb(21, 101, 192); style = Paint.Style.FILL; isAntiAlias = true }
    val areaPaint = Paint().apply { color = Color.argb(46, 21, 101, 192); style = Paint.Style.FILL }
    val outagePaint = Paint().apply { color = Color.argb(60, 211, 47, 47) }
    val leqPaint = Paint().apply {
        color = Color.rgb(46, 125, 50); strokeWidth = 1.3f; style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
    }
    val pinAussenPaint = Paint().apply { color = Color.rgb(255, 160, 0); isAntiAlias = true }
    val pinInnenPaint = Paint().apply { color = Color.rgb(255, 213, 79); isAntiAlias = true }

    // 1. Y-Achsen-Gitterlinien und -Beschriftung
    val ySteps = if (dbSpanne >= 30) 4 else 3
    val dbStep = dbSpanne / ySteps
    for (i in 0..ySteps) {
        val dbVal = minScaleDb + i * dbStep
        val yPos = y(dbVal)
        canvas.drawLine(plotLeft, yPos, plotRight, yPos, gridPaint)
        canvas.drawText("${dbVal.toInt()}", left, yPos + 3f, textPaint)
    }

    // 2. LAeq-Referenzlinie (gestrichelt)
    laeqDb?.let { leq ->
        val leqY = y(leq)
        if (leqY in plotTop..plotBottom) canvas.drawLine(plotLeft, leqY, plotRight, leqY, leqPaint)
    }

    // 3. Ausfallbänder
    ausfallbaender.forEach { band ->
        val vonSek = ((band.von - von) / 1000).coerceIn(0, gesamtSekunden)
        val bisSek = (((band.bis ?: bis) - von) / 1000).coerceIn(0, gesamtSekunden)
        val xStart = x(vonSek).coerceIn(plotLeft, plotRight)
        val xEnd = x(bisSek).coerceIn(plotLeft, plotRight)
        if (xEnd > xStart) canvas.drawRect(xStart, plotTop, xEnd, plotBottom, outagePaint)
    }

    // 4. Pegeldaten in Segmente aufteilen (Datenlücken wie im Original erkannt) & zeichnen
    val spaltenAbstandSek = if (spalten.size > 1) {
        (spalten.last().zeitOffsetSekunden - spalten.first().zeitOffsetSekunden) / (spalten.size - 1)
    } else {
        60L
    }
    val maxLueckeSekunden = maxOf(180L, (spaltenAbstandSek * 2.5).toLong())

    val segmente = mutableListOf<MutableList<ChartSpalte>>()
    var aktuellesSegment = mutableListOf<ChartSpalte>()
    spalten.forEach { spalte ->
        if (aktuellesSegment.isEmpty()) {
            aktuellesSegment.add(spalte)
        } else {
            val deltaSek = spalte.zeitOffsetSekunden - aktuellesSegment.last().zeitOffsetSekunden
            if (deltaSek > maxLueckeSekunden) {
                segmente.add(aktuellesSegment)
                aktuellesSegment = mutableListOf(spalte)
            } else {
                aktuellesSegment.add(spalte)
            }
        }
    }
    if (aktuellesSegment.isNotEmpty()) segmente.add(aktuellesSegment)

    segmente.forEach { segment ->
        if (segment.size == 1) {
            val spalte = segment.first()
            canvas.drawCircle(x(spalte.zeitOffsetSekunden), y(spalte.mittelDb), 2f, curveDotPaint)
        } else {
            val bandPfad = Path()
            segment.forEachIndexed { index, spalte ->
                val px = x(spalte.zeitOffsetSekunden)
                if (index == 0) bandPfad.moveTo(px, y(spalte.maxDb)) else bandPfad.lineTo(px, y(spalte.maxDb))
            }
            for (index in segment.indices.reversed()) {
                bandPfad.lineTo(x(segment[index].zeitOffsetSekunden), y(segment[index].minDb))
            }
            bandPfad.close()
            canvas.drawPath(bandPfad, areaPaint)

            val mittelPfad = Path()
            segment.forEachIndexed { index, spalte ->
                val px = x(spalte.zeitOffsetSekunden)
                val py = y(spalte.mittelDb)
                if (index == 0) mittelPfad.moveTo(px, py) else mittelPfad.lineTo(px, py)
            }
            canvas.drawPath(mittelPfad, curveLinePaint)
        }
    }

    // 5. Ereignis-Pins auf der Zeitachse
    events.forEach { event ->
        val eventSek = ((event.timestamp - von) / 1000).coerceIn(0, gesamtSekunden)
        val eventX = x(eventSek)
        if (eventX in plotLeft..plotRight) {
            val eventDb = event.calibratedDbA ?: event.dbValue
            val eventY = y(eventDb)
            canvas.drawCircle(eventX, eventY, 4f, pinAussenPaint)
            canvas.drawCircle(eventX, eventY, 2.2f, pinInnenPaint)
        }
    }

    // 6. X-Achsen-Gitter & Datumsbeschriftung
    val xTicks = 4
    val datumsFormat = SimpleDateFormat("dd.MM.", Locale.getDefault())
    for (i in 0..xTicks) {
        val tickSek = (gesamtSekunden * i) / xTicks
        val xPos = x(tickSek)
        canvas.drawLine(xPos, plotBottom, xPos, plotBottom + 4f, gridPaint)
        val label = datumsFormat.format(Date(von + tickSek * 1000))
        canvas.drawText(label, (xPos - 12f).coerceAtLeast(plotLeft), plotBottom + xAxisHeight - 2f, textPaint)
    }

    canvas.drawRect(plotLeft, plotTop, plotRight, plotBottom, rahmenPaint)
}
