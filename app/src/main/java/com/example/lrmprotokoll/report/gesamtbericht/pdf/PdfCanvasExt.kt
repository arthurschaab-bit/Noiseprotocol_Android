package com.example.lrmprotokoll.report.gesamtbericht.pdf

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/**
 * Hilfsfunktionen und Design-Tokens für Vektor-PDF-Rendering im DIN A4 Format (595 x 842 pt).
 */
object PdfCanvasExt {

    const val PAGE_WIDTH = 595f
    const val PAGE_HEIGHT = 842f
    const val MARGIN_LEFT = 38f
    const val MARGIN_RIGHT = 38f
    const val MARGIN_TOP = 36f
    const val MARGIN_BOTTOM = 36f
    const val CONTENT_WIDTH = PAGE_WIDTH - MARGIN_LEFT - MARGIN_RIGHT

    // Farbpalette
    val COLOR_PRIMARY = Color.rgb(15, 23, 42)       // Slate 900
    val COLOR_SECONDARY = Color.rgb(30, 64, 175)    // Blue 800
    val COLOR_ACCENT_BLUE = Color.rgb(37, 99, 235)  // Blue 600
    val COLOR_BG_LIGHT = Color.rgb(248, 250, 252)   // Slate 50
    val COLOR_BG_CARD = Color.rgb(241, 245, 249)    // Slate 100
    val COLOR_TEXT_MAIN = Color.rgb(15, 23, 42)
    val COLOR_TEXT_MUTED = Color.rgb(100, 116, 139) // Slate 500
    val COLOR_BORDER = Color.rgb(203, 213, 225)     // Slate 300
    val COLOR_RED_EXCEED = Color.rgb(220, 38, 38)   // Red 600
    val COLOR_RED_BG = Color.rgb(254, 242, 242)     // Red 50
    val COLOR_GREEN = Color.rgb(22, 163, 74)        // Green 600
    val COLOR_GREEN_BG = Color.rgb(240, 253, 244)   // Green 50
    val COLOR_AMBER = Color.rgb(217, 119, 6)        // Amber 600
    val COLOR_AMBER_BG = Color.rgb(254, 252, 232)   // Amber 50

    fun createPaint(
        color: Int = COLOR_TEXT_MAIN,
        textSize: Float = 10f,
        isBold: Boolean = false,
        align: Paint.Align = Paint.Align.LEFT
    ): Paint {
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.textSize = textSize
            this.typeface = if (isBold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
            this.textAlign = align
        }
    }

    /**
     * Zeichnet die Kopfzeile der Berichtsseite.
     */
    fun drawHeader(
        canvas: Canvas,
        pageCategory: String,
        pageTitle: String,
        pageNum: Int,
        docVersion: String = "LÄRMPROTOKOLL · SCHALLMESSUNG"
    ) {
        val paintText = createPaint(COLOR_TEXT_MUTED, textSize = 7.5f)
        canvas.drawText(docVersion, MARGIN_LEFT, MARGIN_TOP - 10f, paintText)

        val paintRight = createPaint(COLOR_SECONDARY, textSize = 7.5f, isBold = true, align = Paint.Align.RIGHT)
        canvas.drawText(pageCategory.uppercase(), PAGE_WIDTH - MARGIN_RIGHT, MARGIN_TOP - 10f, paintRight)

        // Trennlinie
        val paintLine = Paint().apply {
            color = COLOR_BORDER
            strokeWidth = 0.8f
        }
        canvas.drawLine(MARGIN_LEFT, MARGIN_TOP - 4f, PAGE_WIDTH - MARGIN_RIGHT, MARGIN_TOP - 4f, paintLine)

        // Seitentitel
        if (pageTitle.isNotBlank()) {
            val paintTitle = createPaint(COLOR_PRIMARY, textSize = 15f, isBold = true)
            canvas.drawText(pageTitle, MARGIN_LEFT, MARGIN_TOP + 18f, paintTitle)
        }
    }

    /**
     * Zeichnet die Fußzeile mit Revisions- und Seitennummer.
     */
    fun drawFooter(
        canvas: Canvas,
        pageNum: Int,
        totalPages: Int,
        metaText: String = "Revisionssichere Auswertung nach TA Lärm / AVV Baulärm"
    ) {
        val y = PAGE_HEIGHT - MARGIN_BOTTOM + 14f
        val paintLine = Paint().apply {
            color = COLOR_BORDER
            strokeWidth = 0.8f
        }
        canvas.drawLine(MARGIN_LEFT, y - 8f, PAGE_WIDTH - MARGIN_RIGHT, y - 8f, paintLine)

        val paintMeta = createPaint(COLOR_TEXT_MUTED, textSize = 7.5f)
        canvas.drawText(metaText, MARGIN_LEFT, y + 4f, paintMeta)

        val paintPage = createPaint(COLOR_TEXT_MUTED, textSize = 7.5f, isBold = true, align = Paint.Align.RIGHT)
        canvas.drawText("Seite $pageNum von $totalPages", PAGE_WIDTH - MARGIN_RIGHT, y + 4f, paintPage)
    }

    /**
     * Zeichnet umbrochenen Text und liefert die nächste Y-Position.
     */
    fun drawParagraph(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        width: Float,
        paint: Paint,
        lineSpacingExtra: Float = 2f
    ): Float {
        val textPaint = TextPaint(paint)
        val staticLayout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width.toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(lineSpacingExtra, 1f)
            .setIncludePad(false)
            .build()

        canvas.save()
        canvas.translate(x, y)
        staticLayout.draw(canvas)
        canvas.restore()

        return y + staticLayout.height
    }

    /**
     * Zeichnet eine abgerundete Info-Box mit Titel, Inhalt und optionalem Badge.
     */
    fun drawCard(
        canvas: Canvas,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        bgColor: Int = COLOR_BG_CARD,
        borderColor: Int = COLOR_BORDER,
        cornerRadius: Float = 6f
    ) {
        val rect = RectF(x, y, x + width, y + height)
        val bgPaint = Paint().apply {
            color = bgColor
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)

        if (borderColor != Color.TRANSPARENT) {
            val strokePaint = Paint().apply {
                color = borderColor
                style = Paint.Style.STROKE
                strokeWidth = 0.8f
            }
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, strokePaint)
        }
    }

    /**
     * Zeichnet eine Tabelle mit Kopfzeile und alternierenden Zeilen.
     */
    fun drawTable(
        canvas: Canvas,
        x: Float,
        y: Float,
        colWidths: List<Float>,
        headers: List<String>,
        rows: List<List<String>>,
        rowHeight: Float = 16f,
        alignments: List<Paint.Align> = emptyList()
    ): Float {
        var curY = y
        val totalWidth = colWidths.sum()

        // 1. Header
        val headerBgPaint = Paint().apply {
            color = COLOR_PRIMARY
            style = Paint.Style.FILL
        }
        canvas.drawRect(x, curY, x + totalWidth, curY + rowHeight, headerBgPaint)

        val headerTextPaint = createPaint(Color.WHITE, textSize = 8f, isBold = true)
        var curX = x
        for (i in headers.indices) {
            val w = colWidths[i]
            val align = alignments.getOrNull(i) ?: Paint.Align.LEFT
            headerTextPaint.textAlign = align
            val textX = if (align == Paint.Align.RIGHT) curX + w - 4f else curX + 4f
            canvas.drawText(headers[i], textX, curY + rowHeight - 5f, headerTextPaint)
            curX += w
        }
        curY += rowHeight

        // 2. Rows
        val rowBgPaintAlt = Paint().apply {
            color = COLOR_BG_CARD
            style = Paint.Style.FILL
        }
        val rowTextPaint = createPaint(COLOR_TEXT_MAIN, textSize = 7.5f)
        val linePaint = Paint().apply {
            color = COLOR_BORDER
            strokeWidth = 0.5f
        }

        for (rIdx in rows.indices) {
            val row = rows[rIdx]
            if (rIdx % 2 == 1) {
                canvas.drawRect(x, curY, x + totalWidth, curY + rowHeight, rowBgPaintAlt)
            }

            curX = x
            for (cIdx in row.indices) {
                val w = colWidths.getOrNull(cIdx) ?: 50f
                val text = row[cIdx]
                val align = alignments.getOrNull(cIdx) ?: Paint.Align.LEFT
                rowTextPaint.textAlign = align

                val textX = if (align == Paint.Align.RIGHT) curX + w - 4f else curX + 4f
                canvas.drawText(text, textX, curY + rowHeight - 5f, rowTextPaint)
                curX += w
            }

            canvas.drawLine(x, curY + rowHeight, x + totalWidth, curY + rowHeight, linePaint)
            curY += rowHeight
        }

        return curY
    }

    /**
     * Zeichnet ein Status-Pill (z. B. "Überschritten", "Eingehalten", "Klasse 2").
     */
    fun drawStatusPill(
        canvas: Canvas,
        x: Float,
        y: Float,
        text: String,
        bgColor: Int,
        textColor: Int
    ) {
        val paintText = createPaint(textColor, textSize = 7.5f, isBold = true)
        val textWidth = paintText.measureText(text)
        val pillWidth = textWidth + 10f
        val pillHeight = 12f

        val rect = RectF(x, y, x + pillWidth, y + pillHeight)
        val paintBg = Paint().apply {
            color = bgColor
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(rect, 6f, 6f, paintBg)
        canvas.drawText(text, x + 5f, y + pillHeight - 3f, paintText)
    }
}
