package com.example.lrmprotokoll.report.pdf

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/**
 * Gemeinsame Zeichenbausteine fuer alle PDF-Berichte: Farben, Schriften, Kopf- und Fusszeile,
 * umbrochener Text, Karten und Tabellen.
 *
 * **Herkunft:** uebernommen und angepasst aus `PdfCanvasExt` von PR #78
 * ("Revisionssicherer Gesamtbericht"). Aus diesem PR wird bewusst NUR die Darstellungsschicht
 * uebernommen - die dortige Rechen- und Compliance-Schicht (`AkustikRechner`,
 * `GesamtberichtAggregator`) bleibt aussen vor, weil sie einen zweiten Kennwerte-Rechner neben
 * dem vorhandenen, getesteten
 * [com.example.lrmprotokoll.messreihe.AkustischeKennwerte] aufmacht und Aussagen ins Dokument
 * druckt, die der Code nicht einloest.
 *
 * Angepasst gegenueber der Vorlage:
 * - Seitenmasse und Raender kommen aus [Seitenlauf], damit Umbruchrechnung und Zeichnen
 *   dieselben Werte benutzen und nicht auseinanderlaufen koennen.
 * - [fusszeile] nimmt die Gesamtseitenzahl als Pflichtangabe statt sie zu schaetzen.
 * - [tabelle] bricht ueber Seiten um, statt am unteren Rand abzureissen.
 *
 * Nicht uebernommen: der `ChartRenderer` aus PR #78. Ein funktionierender, aus
 * [com.example.lrmprotokoll.ui.PegelverlaufChart] portierter Chart existiert bereits in
 * [com.example.lrmprotokoll.report.PeriodenBerichtExport]; einen zweiten daneben zu stellen
 * waere genau die Doppelung, die dieser Umbau beseitigen soll.
 */
object BerichtLayout {

    val COLOR_PRIMARY = Color.rgb(15, 23, 42)
    val COLOR_SECONDARY = Color.rgb(30, 64, 175)
    val COLOR_BG_CARD = Color.rgb(241, 245, 249)
    val COLOR_TEXT_MAIN = Color.rgb(15, 23, 42)
    val COLOR_TEXT_MUTED = Color.rgb(100, 116, 139)
    val COLOR_BORDER = Color.rgb(203, 213, 225)
    val COLOR_WARN = Color.rgb(217, 119, 6)
    val COLOR_WARN_BG = Color.rgb(254, 252, 232)

    fun paint(
        color: Int = COLOR_TEXT_MAIN,
        textSize: Float = 10f,
        fett: Boolean = false,
        ausrichtung: Paint.Align = Paint.Align.LEFT,
    ): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        this.textSize = textSize
        this.typeface = if (fett) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
        this.textAlign = ausrichtung
    }

    /** Kopfzeile mit Dokumentkennung links und Abschnittsname rechts. */
    fun kopfzeile(canvas: Canvas, abschnitt: String) {
        val links = paint(COLOR_TEXT_MUTED, textSize = 7.5f)
        canvas.drawText("LÄRMPROTOKOLL", Seitenlauf.RAND_LINKS, Seitenlauf.RAND_OBEN - 16f, links)

        val rechts = paint(COLOR_SECONDARY, textSize = 7.5f, fett = true, ausrichtung = Paint.Align.RIGHT)
        canvas.drawText(
            abschnitt.uppercase(),
            Seitenlauf.SEITE_BREITE - Seitenlauf.RAND_RECHTS,
            Seitenlauf.RAND_OBEN - 16f,
            rechts,
        )

        val linie = Paint().apply { color = COLOR_BORDER; strokeWidth = 0.8f }
        canvas.drawLine(
            Seitenlauf.RAND_LINKS, Seitenlauf.RAND_OBEN - 10f,
            Seitenlauf.SEITE_BREITE - Seitenlauf.RAND_RECHTS, Seitenlauf.RAND_OBEN - 10f, linie,
        )
    }

    /**
     * Fusszeile mit "Seite N von M". [gesamtSeiten] ist Pflicht und wird vom Aufrufer aus
     * [Seitenlauf.zaehleSeiten] ermittelt - nicht geschaetzt.
     */
    fun fusszeile(canvas: Canvas, seitenNummer: Int, gesamtSeiten: Int, hinweis: String = "") {
        val y = Seitenlauf.SEITE_HOEHE - Seitenlauf.RAND_UNTEN + 26f
        val linie = Paint().apply { color = COLOR_BORDER; strokeWidth = 0.8f }
        canvas.drawLine(
            Seitenlauf.RAND_LINKS, y - 12f,
            Seitenlauf.SEITE_BREITE - Seitenlauf.RAND_RECHTS, y - 12f, linie,
        )

        if (hinweis.isNotBlank()) {
            canvas.drawText(hinweis, Seitenlauf.RAND_LINKS, y, paint(COLOR_TEXT_MUTED, textSize = 7.5f))
        }
        canvas.drawText(
            "Seite $seitenNummer von $gesamtSeiten",
            Seitenlauf.SEITE_BREITE - Seitenlauf.RAND_RECHTS,
            y,
            paint(COLOR_TEXT_MUTED, textSize = 7.5f, fett = true, ausrichtung = Paint.Align.RIGHT),
        )
    }

    /**
     * Hoehe, die [text] umbrochen auf [breite] einnehmen wird - fuer die Umbruchrechnung, bevor
     * gezeichnet wird. Nutzt dieselbe [StaticLayout]-Konfiguration wie [absatz], damit
     * Vorausberechnung und Ausgabe nicht auseinanderlaufen.
     */
    fun absatzHoehe(text: String, breite: Float, paint: Paint, zeilenAbstand: Float = 2f): Float =
        layout(text, breite, paint, zeilenAbstand).height.toFloat()

    /** Zeichnet umbrochenen Text an [y] und liefert die Hoehe zurueck. */
    fun absatz(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        breite: Float,
        paint: Paint,
        zeilenAbstand: Float = 2f,
    ): Float {
        val l = layout(text, breite, paint, zeilenAbstand)
        canvas.save()
        canvas.translate(x, y)
        l.draw(canvas)
        canvas.restore()
        return l.height.toFloat()
    }

    private fun layout(text: String, breite: Float, paint: Paint, zeilenAbstand: Float): StaticLayout =
        StaticLayout.Builder
            .obtain(text, 0, text.length, TextPaint(paint), breite.toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(zeilenAbstand, 1f)
            .setIncludePad(false)
            .build()

    /** Abgerundete Hinweisbox. */
    fun karte(
        canvas: Canvas,
        x: Float,
        y: Float,
        breite: Float,
        hoehe: Float,
        fuellFarbe: Int = COLOR_BG_CARD,
        rahmenFarbe: Int = COLOR_BORDER,
        radius: Float = 6f,
    ) {
        val rect = RectF(x, y, x + breite, y + hoehe)
        canvas.drawRoundRect(rect, radius, radius, Paint().apply { color = fuellFarbe; style = Paint.Style.FILL })
        if (rahmenFarbe != Color.TRANSPARENT) {
            canvas.drawRoundRect(
                rect, radius, radius,
                Paint().apply { color = rahmenFarbe; style = Paint.Style.STROKE; strokeWidth = 0.8f },
            )
        }
    }

    /** Kopfzeile einer Tabelle. Der Aufrufer platziert sie ueber [Seitenlauf.platziere]. */
    fun tabellenKopf(canvas: Canvas, x: Float, y: Float, spalten: List<Float>, titel: List<String>, zeilenHoehe: Float) {
        canvas.drawRect(x, y, x + spalten.sum(), y + zeilenHoehe, Paint().apply { color = COLOR_PRIMARY })
        val p = paint(Color.WHITE, textSize = 8f, fett = true)
        var cx = x
        titel.forEachIndexed { i, t ->
            canvas.drawText(t, cx + 4f, y + zeilenHoehe - 5f, p)
            cx += spalten.getOrElse(i) { 50f }
        }
    }

    /** Eine Tabellenzeile. [geradeZeile] steuert nur die Hintergrundschattierung. */
    fun tabellenZeile(
        canvas: Canvas,
        x: Float,
        y: Float,
        spalten: List<Float>,
        werte: List<String>,
        zeilenHoehe: Float,
        geradeZeile: Boolean,
    ) {
        val breite = spalten.sum()
        if (geradeZeile) {
            canvas.drawRect(x, y, x + breite, y + zeilenHoehe, Paint().apply { color = COLOR_BG_CARD })
        }
        val p = paint(COLOR_TEXT_MAIN, textSize = 7.5f)
        var cx = x
        werte.forEachIndexed { i, w ->
            canvas.drawText(w, cx + 4f, y + zeilenHoehe - 5f, p)
            cx += spalten.getOrElse(i) { 50f }
        }
        canvas.drawLine(x, y + zeilenHoehe, x + breite, y + zeilenHoehe, Paint().apply {
            color = COLOR_BORDER; strokeWidth = 0.5f
        })
    }
}
