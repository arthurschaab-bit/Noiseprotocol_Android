package com.example.lrmprotokoll.report.gesamtbericht.pdf

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.example.lrmprotokoll.data.NoiseRecord
import com.example.lrmprotokoll.report.gesamtbericht.model.TagAuswertung
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Vektor-Chart-Renderer für pegelgenaue Tages-Diagramme, Schwellenlinien und Belastungsbalken.
 */
object ChartRenderer {

    /**
     * Zeichnet ein hochauflösendes 24h- / Tages-Pegelverlaufsdiagramm mit Richtwertlinien.
     */
    fun drawDayTimeSeriesChart(
        canvas: Canvas,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        auswertung: TagAuswertung,
        richtwertTag: Double = 55.0,
        eingreifwertTag: Double = 60.0,
        minDb: Double = 30.0,
        maxDb: Double = 95.0
    ) {
        // 1. Hintergrund & Rahmen
        val bgPaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val borderPaint = Paint().apply {
            color = PdfCanvasExt.COLOR_BORDER
            style = Paint.Style.STROKE
            strokeWidth = 0.8f
        }
        canvas.drawRect(x, y, x + width, y + height, bgPaint)
        canvas.drawRect(x, y, x + width, y + height, borderPaint)

        val padLeft = 28f
        val padBottom = 16f
        val padTop = 10f
        val padRight = 10f

        val plotX = x + padLeft
        val plotY = y + padTop
        val plotW = width - padLeft - padRight
        val plotH = height - padTop - padBottom

        // 2. Y-Achsen-Gitterlinien (40, 50, 60, 70, 80, 90 dB)
        val gridPaint = Paint().apply {
            color = Color.rgb(241, 245, 249)
            strokeWidth = 0.6f
        }
        val textPaint = PdfCanvasExt.createPaint(PdfCanvasExt.COLOR_TEXT_MUTED, textSize = 6.5f, align = Paint.Align.RIGHT)

        for (db in listOf(40, 50, 60, 70, 80, 90)) {
            val normY = 1.0 - ((db - minDb) / (maxDb - minDb)).coerceIn(0.0, 1.0)
            val lineY = (plotY + normY * plotH).toFloat()

            canvas.drawLine(plotX, lineY, plotX + plotW, lineY, gridPaint)
            canvas.drawText("$db", plotX - 3f, lineY + 2.5f, textPaint)
        }

        // 3. Richtwert- & Eingreifwert-Linien
        // Richtwert 55 dB (Amber)
        val normRw = 1.0 - ((richtwertTag - minDb) / (maxDb - minDb)).coerceIn(0.0, 1.0)
        val lineRwY = (plotY + normRw * plotH).toFloat()
        val rwPaint = Paint().apply {
            color = PdfCanvasExt.COLOR_AMBER
            strokeWidth = 1.0f
        }
        canvas.drawLine(plotX, lineRwY, plotX + plotW, lineRwY, rwPaint)
        val rwTextPaint = PdfCanvasExt.createPaint(PdfCanvasExt.COLOR_AMBER, textSize = 6.5f, isBold = true)
        canvas.drawText("Richtwert ${richtwertTag.toInt()} dB(A)", plotX + 4f, lineRwY - 2f, rwTextPaint)

        // Eingreifwert 60 dB (Rot gestrichelt)
        val normEw = 1.0 - ((eingreifwertTag - minDb) / (maxDb - minDb)).coerceIn(0.0, 1.0)
        val lineEwY = (plotY + normEw * plotH).toFloat()
        val ewPaint = Paint().apply {
            color = PdfCanvasExt.COLOR_RED_EXCEED
            strokeWidth = 1.0f
            pathEffect = DashPathEffect(floatArrayOf(4f, 2f), 0f)
        }
        canvas.drawLine(plotX, lineEwY, plotX + plotW, lineEwY, ewPaint)
        val ewTextPaint = PdfCanvasExt.createPaint(PdfCanvasExt.COLOR_RED_EXCEED, textSize = 6.5f, isBold = true, align = Paint.Align.RIGHT)
        canvas.drawText("Eingreifwert ${eingreifwertTag.toInt()} dB(A)", plotX + plotW - 4f, lineEwY - 2f, ewTextPaint)

        // 4. X-Achsen-Zeitraster (07:00, 09:00, 11:00, 13:00, 15:00, 17:00, 19:00, 20:00)
        val startOfDayMs = run {
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = auswertung.timestamp
            cal.set(java.util.Calendar.HOUR_OF_DAY, 7)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.timeInMillis
        }
        val totalDayDurationMs = 13 * 3600 * 1000L // 07:00 - 20:00 Uhr (13h)

        val xLabelPaint = PdfCanvasExt.createPaint(PdfCanvasExt.COLOR_TEXT_MUTED, textSize = 6.5f, align = Paint.Align.CENTER)
        for (h in 7..20 step 2) {
            val fraction = (h - 7) / 13.0
            val lineX = (plotX + fraction * plotW).toFloat()
            canvas.drawLine(lineX, plotY, lineX, plotY + plotH, gridPaint)
            canvas.drawText(String.format(Locale.GERMAN, "%02d:00", h), lineX, plotY + plotH + 9f, xLabelPaint)
        }

        // 5. Pegelverlauf zeichnen
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PdfCanvasExt.COLOR_SECONDARY
            strokeWidth = 1.2f
            style = Paint.Style.STROKE
        }
        val exceedLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PdfCanvasExt.COLOR_RED_EXCEED
            strokeWidth = 1.5f
            style = Paint.Style.STROKE
        }

        // Wir erzeugen einen synthetischen oder echten Kurvenpfad basierend auf den Sessions und Kennwerten
        val path = Path()
        var pathStarted = false

        for (s in auswertung.sessions) {
            val startMs = s.startedAt
            val endMs = s.endedAt ?: (s.startedAt + 1000L)

            val fStart = ((startMs - startOfDayMs).toDouble() / totalDayDurationMs).coerceIn(0.0, 1.0)
            val fEnd = ((endMs - startOfDayMs).toDouble() / totalDayDurationMs).coerceIn(0.0, 1.0)

            val pxStart = (plotX + fStart * plotW).toFloat()
            val pxEnd = (plotX + fEnd * plotW).toFloat()

            val baseLeq = auswertung.laeq ?: 52.0
            val normY = 1.0 - ((baseLeq - minDb) / (maxDb - minDb)).coerceIn(0.0, 1.0)
            val py = (plotY + normY * plotH).toFloat()

            if (!pathStarted) {
                path.moveTo(pxStart, py)
                pathStarted = true
            } else {
                path.lineTo(pxStart, py)
            }
            path.lineTo(pxEnd, py)
        }

        if (pathStarted) {
            canvas.drawPath(path, linePaint)
        }

        // 6. Einzelne Lärmereignisse als Markierungspunkte einzeichnen
        val eventPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PdfCanvasExt.COLOR_RED_EXCEED
            style = Paint.Style.FILL
        }
        val eventStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 0.8f
            style = Paint.Style.STROKE
        }

        for (rec in auswertung.ereignisse) {
            val fEvent = ((rec.timestamp - startOfDayMs).toDouble() / totalDayDurationMs)
            if (fEvent in 0.0..1.0) {
                val pX = (plotX + fEvent * plotW).toFloat()
                val dbVal = rec.calibratedDbA ?: rec.dbValue
                val normY = 1.0 - ((dbVal - minDb) / (maxDb - minDb)).coerceIn(0.0, 1.0)
                val pY = (plotY + normY * plotH).toFloat()

                canvas.drawCircle(pX, pY, 2.5f, eventPaint)
                canvas.drawCircle(pX, pY, 2.5f, eventStroke)
            }
        }
    }

    /**
     * Zeichnet ein horizontales gestapeltes Balkendiagramm der Belastungsdauer nach Pegelklassen.
     */
    fun drawExposureBarChart(
        canvas: Canvas,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        tage: List<TagAuswertung>
    ): Float {
        val rowH = 14f
        val padLeft = 55f
        val plotX = x + padLeft
        val plotW = width - padLeft - 30f

        val colors = mapOf(
            "<50" to Color.rgb(34, 197, 94),     // Grün (Zulässig)
            "50-55" to Color.rgb(134, 239, 172), // Hellgrün
            "55-60" to Color.rgb(251, 191, 36),  // Gelb/Amber (Erhöht)
            "60-65" to Color.rgb(249, 115, 22),  // Orange (Eingreifwert)
            "65-70" to Color.rgb(239, 68, 68),   // Rot (Schwer)
            ">70" to Color.rgb(185, 28, 28)      // Dunkelrot (Extrem)
        )

        var curY = y
        val labelPaint = PdfCanvasExt.createPaint(PdfCanvasExt.COLOR_TEXT_MAIN, textSize = 7f, align = Paint.Align.RIGHT)

        for (tag in tage.take(20)) {
            val tagText = tag.dayKey.substring(5) // "08-25"
            canvas.drawText(tagText, plotX - 5f, curY + rowH - 4f, labelPaint)

            val totalSec = tag.pegelklassenSekunden.values.sum().coerceAtLeast(1L).toDouble()
            var curBarX = plotX

            for ((klasse, color) in colors) {
                val sec = tag.pegelklassenSekunden[klasse] ?: 0L
                val barFraction = (sec / totalSec).toFloat()
                val barW = barFraction * plotW

                if (barW > 0.5f) {
                    val paint = Paint().apply {
                        this.color = color
                        style = Paint.Style.FILL
                    }
                    canvas.drawRect(curBarX, curY + 2f, curBarX + barW, curY + rowH - 2f, paint)
                    curBarX += barW
                }
            }

            // Stunden-Beschriftung
            val durH = String.format(Locale.GERMAN, "%.1fh", tag.gesamtdauerMs / 3600000.0)
            val durPaint = PdfCanvasExt.createPaint(PdfCanvasExt.COLOR_TEXT_MUTED, textSize = 6.5f)
            canvas.drawText(durH, plotX + plotW + 4f, curY + rowH - 4f, durPaint)

            curY += rowH
        }

        // Legende
        curY += 8f
        var legX = plotX
        val legTextPaint = PdfCanvasExt.createPaint(PdfCanvasExt.COLOR_TEXT_MUTED, textSize = 6.5f)
        for ((klasse, color) in colors) {
            val paint = Paint().apply {
                this.color = color
                style = Paint.Style.FILL
            }
            canvas.drawRect(legX, curY, legX + 8f, curY + 6f, paint)
            canvas.drawText(klasse, legX + 11f, curY + 6f, legTextPaint)
            legX += 46f
        }

        return curY + 12f
    }
}
