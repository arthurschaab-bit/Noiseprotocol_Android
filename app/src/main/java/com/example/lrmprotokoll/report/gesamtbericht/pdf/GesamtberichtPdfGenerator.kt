package com.example.lrmprotokoll.report.gesamtbericht.pdf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.example.lrmprotokoll.report.gesamtbericht.model.GesamtberichtData
import com.example.lrmprotokoll.report.gesamtbericht.model.TagAuswertung
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Erzeugt den vollständigen, revisionssicheren Gesamtbericht als DIN A4 PDF
 * exakt nach dem Vorbild von Gesamtbericht_Schallmessung_Zeppelinstrasse_v10.pdf.
 */
class GesamtberichtPdfGenerator(private val context: Context) {

    suspend fun generierePdf(
        data: GesamtberichtData,
        zielDatei: File,
        onProgress: ((aktuelleSeite: Int, gesamtSeiten: Int) -> Unit)? = null
    ): File = withContext(Dispatchers.IO) {
        val doc = PdfDocument()
        val totalStaticPages = 5 // Cover, Kurzfassung, Methodik/Setup, Kernbefunde, Belastungsdauer
        val totalDailyPages = data.tage.size
        val totalManifestPages = 1
        val totalPages = totalStaticPages + totalDailyPages + totalManifestPages

        var pageNum = 1

        // ==========================================
        // SEITE 1: DECKBLATT (COVER PAGE)
        // ==========================================
        onProgress?.invoke(pageNum, totalPages)
        val p1Info = PdfDocument.PageInfo.Builder(PdfCanvasExt.PAGE_WIDTH.toInt(), PdfCanvasExt.PAGE_HEIGHT.toInt(), pageNum).create()
        val p1 = doc.startPage(p1Info)
        renderCoverPage(p1.canvas, data, pageNum, totalPages)
        doc.finishPage(p1)
        pageNum++

        // ==========================================
        // SEITE 2: JURISTISCHE KURZFASSUNG
        // ==========================================
        onProgress?.invoke(pageNum, totalPages)
        val p2Info = PdfDocument.PageInfo.Builder(PdfCanvasExt.PAGE_WIDTH.toInt(), PdfCanvasExt.PAGE_HEIGHT.toInt(), pageNum).create()
        val p2 = doc.startPage(p2Info)
        renderLegalSummaryPage(p2.canvas, data, pageNum, totalPages)
        doc.finishPage(p2)
        pageNum++

        // ==========================================
        // SEITE 3: RECHTLICHE GRUNDLAGEN & METHODIK
        // ==========================================
        onProgress?.invoke(pageNum, totalPages)
        val p3Info = PdfDocument.PageInfo.Builder(PdfCanvasExt.PAGE_WIDTH.toInt(), PdfCanvasExt.PAGE_HEIGHT.toInt(), pageNum).create()
        val p3 = doc.startPage(p3Info)
        renderMethodologyPage(p3.canvas, data, pageNum, totalPages)
        doc.finishPage(p3)
        pageNum++

        // ==========================================
        // SEITE 4: KERNBEFUNDE (TABELLE ALLER MESSTAGE)
        // ==========================================
        onProgress?.invoke(pageNum, totalPages)
        val p4Info = PdfDocument.PageInfo.Builder(PdfCanvasExt.PAGE_WIDTH.toInt(), PdfCanvasExt.PAGE_HEIGHT.toInt(), pageNum).create()
        val p4 = doc.startPage(p4Info)
        renderKeyFindingsPage(p4.canvas, data, pageNum, totalPages)
        doc.finishPage(p4)
        pageNum++

        // ==========================================
        // SEITE 5: BELASTUNGSDAUER & LAUTESTE STUNDE
        // ==========================================
        onProgress?.invoke(pageNum, totalPages)
        val p5Info = PdfDocument.PageInfo.Builder(PdfCanvasExt.PAGE_WIDTH.toInt(), PdfCanvasExt.PAGE_HEIGHT.toInt(), pageNum).create()
        val p5 = doc.startPage(p5Info)
        renderExposurePage(p5.canvas, data, pageNum, totalPages)
        doc.finishPage(p5)
        pageNum++

        // ==========================================
        // SEITEN 6+: EINZELSEITE JE MESSTAG
        // ==========================================
        for (tag in data.tage) {
            onProgress?.invoke(pageNum, totalPages)
            val pInfo = PdfDocument.PageInfo.Builder(PdfCanvasExt.PAGE_WIDTH.toInt(), PdfCanvasExt.PAGE_HEIGHT.toInt(), pageNum).create()
            val p = doc.startPage(pInfo)
            renderDailyPage(p.canvas, tag, data.config, pageNum, totalPages)
            doc.finishPage(p)
            pageNum++
        }

        // ==========================================
        // LETZTE SEITE: ROHDATEN-MANIFEST (SHA-256)
        // ==========================================
        onProgress?.invoke(pageNum, totalPages)
        val pmInfo = PdfDocument.PageInfo.Builder(PdfCanvasExt.PAGE_WIDTH.toInt(), PdfCanvasExt.PAGE_HEIGHT.toInt(), pageNum).create()
        val pm = doc.startPage(pmInfo)
        renderManifestPage(pm.canvas, data, pageNum, totalPages)
        doc.finishPage(pm)

        // Schreiben auf Ziel-Datei
        FileOutputStream(zielDatei).use { out ->
            doc.writeTo(out)
        }
        doc.close()

        zielDatei
    }

    // ----------------------------------------------------
    // SEITE 1: DECKBLATT
    // ----------------------------------------------------
    private fun renderCoverPage(canvas: Canvas, data: GesamtberichtData, pageNum: Int, totalPages: Int) {
        // Dekorative Seitenbalken links
        val barPaint = Paint().apply {
            color = PdfCanvasExt.COLOR_SECONDARY
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, 16f, PdfCanvasExt.PAGE_HEIGHT, barPaint)

        var curY = 75f

        val titlePaint = PdfCanvasExt.createPaint(PdfCanvasExt.COLOR_PRIMARY, textSize = 20f, isBold = true)
        curY = PdfCanvasExt.drawParagraph(canvas, data.config.titel, PdfCanvasExt.MARGIN_LEFT + 10f, curY, PdfCanvasExt.CONTENT_WIDTH - 20f, titlePaint, lineSpacingExtra = 4f)

        curY += 8f
        val subPaint = PdfCanvasExt.createPaint(PdfCanvasExt.COLOR_SECONDARY, textSize = 11f, isBold = true)
        curY = PdfCanvasExt.drawParagraph(canvas, data.config.untertitel, PdfCanvasExt.MARGIN_LEFT + 10f, curY, PdfCanvasExt.CONTENT_WIDTH - 20f, subPaint)

        curY += 24f
        val linePaint = Paint().apply {
            color = PdfCanvasExt.COLOR_BORDER
            strokeWidth = 1f
        }
        canvas.drawLine(PdfCanvasExt.MARGIN_LEFT + 10f, curY, PdfCanvasExt.PAGE_WIDTH - PdfCanvasExt.MARGIN_RIGHT, curY, linePaint)
        curY += 20f

        // Metadaten-Box
        val boxWidth = PdfCanvasExt.CONTENT_WIDTH - 10f
        val boxHeight = 220f
        PdfCanvasExt.drawCard(canvas, PdfCanvasExt.MARGIN_LEFT + 10f, curY, boxWidth, boxHeight, bgColor = PdfCanvasExt.COLOR_BG_CARD)

        var metaY = curY + 20f
        val labelPaint = PdfCanvasExt.createPaint(PdfCanvasExt.COLOR_TEXT_MUTED, textSize = 8.5f)
        val valPaint = PdfCanvasExt.createPaint(PdfCanvasExt.COLOR_TEXT_MAIN, textSize = 9.5f, isBold = true)

        val metaFields = listOf(
            "Messstandort / Adresse:" to data.config.messort,
            "Auftraggeber / Anlass:" to data.config.auftraggeber,
            "Gebietscharakteristik:" to data.config.gebietsart,
            "Messgerät / Typ:" to data.config.messgeraet,
            "Mikrofonposition & Höhe:" to "${data.config.mikrofonPosition} · ${data.config.mikrofonHoehe}",
            "Messzeitraum:" to run {
                val firstDay = data.tage.firstOrNull()?.dayKey ?: "–"
                val lastDay = data.tage.lastOrNull()?.dayKey ?: "–"
                "$firstDay bis $lastDay (${data.gesamtMesstage} Messtage)"
            },
            "Normative Grundlagen:" to "TA Lärm · AVV Baulärm · DIN 45681 · BImSchG"
        )

        for ((lbl, v) in metaFields) {
            canvas.drawText(lbl, PdfCanvasExt.MARGIN_LEFT + 24f, metaY, labelPaint)
            canvas.drawText(v, PdfCanvasExt.MARGIN_LEFT + 160f, metaY, valPaint)
            metaY += 26f
        }

        curY += boxHeight + 25f

        // Revisionssicherheits-Hinweis
        val noteBoxH = 110f
        PdfCanvasExt.drawCard(canvas, PdfCanvasExt.MARGIN_LEFT + 10f, curY, boxWidth, noteBoxH, bgColor = PdfCanvasExt.COLOR_GREEN_BG, borderColor = PdfCanvasExt.COLOR_GREEN)

        val noteTitlePaint = PdfCanvasExt.createPaint(PdfCanvasExt.COLOR_GREEN, textSize = 10f, isBold = true)
        canvas.drawText("REVISIONSSICHERE DOKUMENTATION & AUDIT-TRAIL", PdfCanvasExt.MARGIN_LEFT + 24f, curY + 22f, noteTitlePaint)

        val noteText = "Dieser Bericht wurde vollautomatisch aus revisionssicher in der Datenbank erfassten Pegelwerten (Klasse 2, IEC 61672-1) generiert. Jede Messreihe und Audiodatei ist im anhängenden Rohdaten-Manifest mit einem kryptografischen SHA-256 Hash dokumentiert, um Unveränderbarkeit vor Gericht und Genehmigungsbehörden zu gewährleisten."
        val noteTextPaint = PdfCanvasExt.createPaint(PdfCanvasExt.COLOR_TEXT_MAIN, textSize = 8.5f)
        PdfCanvasExt.drawParagraph(canvas, noteText, PdfCanvasExt.MARGIN_LEFT + 24f, curY + 36f, boxWidth - 28f, noteTextPaint, lineSpacingExtra = 3f)

        PdfCanvasExt.drawFooter(canvas, pageNum, totalPages, metaText = "LÄRMPROTOKOLL · SCHALLMESSBERICHT v10")
    }

    // ----------------------------------------------------
    // SEITE 2: JURISTISCHE KURZFASSUNG
    // ----------------------------------------------------
    private fun renderLegalSummaryPage(canvas: Canvas, data: GesamtberichtData, pageNum: Int, totalPages: Int) {
        PdfCanvasExt.drawHeader(canvas, "Zusammenfassung", "Juristische Kurzfassung & Kernbefunde", pageNum)

        var curY = PdfCanvasExt.MARGIN_TOP + 40f

        // 4 KPI Cards
        val cardW = (PdfCanvasExt.CONTENT_WIDTH - 24f) / 3f
        val cardH = 56f

        val kpiList = listOf(
            Triple("Gesamte Messtage", "${data.gesamtMesstage} Tage", "Messungsbestand"),
            Triple("Richtwert-Überschreitung", "${data.ueberschreitungsTageCount} von ${data.gesamtMesstage}", "${String.format(Locale.GERMAN, "%.0f", if (data.gesamtMesstage > 0) (data.ueberschreitungsTageCount.toDouble() / data.gesamtMesstage * 100) else 0.0)}% der Tage"),
            Triple("Höchster Spitzenpegel", "${data.gesamtLmax?.let { String.format(Locale.GERMAN, "%.1f dB", it) } ?: "--.-"}", "Lmax Einzelereignis")
        )

        var curX = PdfCanvasExt.MARGIN_LEFT
        for ((title, v, sub) in kpiList) {
            PdfCanvasExt.drawCard(canvas, curX, curY, cardW, cardH)
            val tPaint = PdfCanvasExt.createPaint(PdfCanvasExt.COLOR_TEXT_MUTED, textSize = 7.5f)
            val vPaint = PdfCanvasExt.createPaint(PdfCanvasExt.COLOR_PRIMARY, textSize = 13f, isBold = true)
            val sPaint = PdfCanvasExt.createPaint(PdfCanvasExt.COLOR_SECONDARY, textSize = 7.5f)

            canvas.drawText(title, curX + 8f, curY + 14f, tPaint)
            canvas.drawText(v, curX + 8f, curY + 34f, vPaint)
            canvas.drawText(sub, curX + 8f, curY + 48f, sPaint)

            curX += cardW + 12f
        }

        curY += cardH + 20f

        // Rechtliche Einordnung Textblöcke
        val sectionTitlePaint = PdfCanvasExt.createPaint(PdfCanvasExt.COLOR_PRIMARY, textSize = 11f, isBold = true)
        val bodyPaint = PdfCanvasExt.createPaint(PdfCanvasExt.COLOR_TEXT_MAIN, textSize = 8.5f)

        canvas.drawText("1. Immissionsrichtwerte & Überschreitungslage", PdfCanvasExt.MARGIN_LEFT, curY, sectionTitlePaint)
        curY += 14f

        val p1 = "Gemäß AVV Baulärm Nr. 3.1.1 beträgt der maßgebliche Immissionsrichtwert für Allgemeine Wohngebiete (WA) tagsüber (07:00–20:00 Uhr) 55 dB(A). Ab einer Überschreitung von mehr als 5 dB(A) (Eingreifwert: 60 dB(A)) sind bauaufsichtliche Maßnahmen bzw. Lärmminderungsauflagen zwingend geboten. An ${data.ueberschreitungsTageCount} von ${data.gesamtMesstage} ausgewerteten Tagen lag der Beurteilungspegel über dem zulässigen Richtwert."
        curY = PdfCanvasExt.drawParagraph(canvas, p1, PdfCanvasExt.MARGIN_LEFT, curY, PdfCanvasExt.CONTENT_WIDTH, bodyPaint, lineSpacingExtra = 3f)

        curY += 16f
        canvas.drawText("2. Spitzenpegel & Belastungsintensität", PdfCanvasExt.MARGIN_LEFT, curY, sectionTitlePaint)
        curY += 14f

        val p2 = "Der höchste dokumentierte Spitzenpegel lag bei ${data.gesamtLmax?.let { String.format(Locale.GERMAN, "%.1f dB(A)", it) } ?: "k.A."}. Die lauteste zusammenhängende 60-Minuten-Periode wurde am ${data.maxLautesteStundeDay ?: "k.A."} mit einem Mittelungspegel von ${data.maxLautesteStundeLeq?.let { String.format(Locale.GERMAN, "%.1f dB(A)", it) } ?: "k.A."} verzeichnet. Dies belegt erhebliche, wiederkehrende Lärmspitzen weit oberhalb der Zumutbarkeitsgrenze."
        curY = PdfCanvasExt.drawParagraph(canvas, p2, PdfCanvasExt.MARGIN_LEFT, curY, PdfCanvasExt.CONTENT_WIDTH, bodyPaint, lineSpacingExtra = 3f)

        curY += 16f
        canvas.drawText("3. Ruhezeiten & Schlafstörungen", PdfCanvasExt.MARGIN_LEFT, curY, sectionTitlePaint)
        curY += 14f

        val p3 = "In den gesetzlichen Ruhezeiten (Nachtzeitraum 20:00/22:00–06:00/07:00 Uhr) wurden insgesamt ${data.gesamtRuhezeitEreignisseCount} Schwellenwertüberschreitungen erfasst. Für den Nachtzeitraum gilt ein Immissionsrichtwert von 40 dB(A) bzw. ein Eingreifwert von 45 dB(A)."
        curY = PdfCanvasExt.drawParagraph(canvas, p3, PdfCanvasExt.MARGIN_LEFT, curY, PdfCanvasExt.CONTENT_WIDTH, bodyPaint, lineSpacingExtra = 3f)

        PdfCanvasExt.drawFooter(canvas, pageNum, totalPages)
    }

    // ----------------------------------------------------
    // SEITE 3: RECHTLICHE GRUNDLAGEN & METHODIK
    // ----------------------------------------------------
    private fun renderMethodologyPage(canvas: Canvas, data: GesamtberichtData, pageNum: Int, totalPages: Int) {
        PdfCanvasExt.drawHeader(canvas, "Methodik & Normen", "Normative Grundlagen & Akustische Berechnungsverfahren", pageNum)

        var curY = PdfCanvasExt.MARGIN_TOP + 40f
        val sectionTitlePaint = PdfCanvasExt.createPaint(PdfCanvasExt.COLOR_PRIMARY, textSize = 10.5f, isBold = true)
        val bodyPaint = PdfCanvasExt.createPaint(PdfCanvasExt.COLOR_TEXT_MAIN, textSize = 8.5f)

        canvas.drawText("Rechtlicher Rahmen: AVV Baulärm & TA Lärm", PdfCanvasExt.MARGIN_LEFT, curY, sectionTitlePaint)
        curY += 14f
        val r1 = "Die Beurteilung richtet sich nach der Allgemeinen Verwaltungsvorschrift zum Schutz gegen Baulärm (AVV Baulärm) sowie der Sechsten Allgemeinen Verwaltungsvorschrift zum Bundes-Immissionsschutzgesetz (TA Lärm). Maßgeblicher Tagbeurteilungszeitraum ist 07:00 bis 20:00 Uhr (13 Stunden). Ruhezeiten und Nachtbetrieb unterliegen verschärften Richtwerten."
        curY = PdfCanvasExt.drawParagraph(canvas, r1, PdfCanvasExt.MARGIN_LEFT, curY, PdfCanvasExt.CONTENT_WIDTH, bodyPaint, lineSpacingExtra = 2.5f)

        curY += 18f
        canvas.drawText("Akustische Kenngrößen & Formeln", PdfCanvasExt.MARGIN_LEFT, curY, sectionTitlePaint)
        curY += 14f

        val formulas = listOf(
            "• Energieäquivalenter Dauerschallpegel (LAeq):" to "LAeq = 10 · lg( 1/T · ∫ 10^(0.1·L(t)) dt ) — Beschreibt den energetischen Mittelwert des Schalldrucks über den Erfassungszeitraum.",
            "• Beurteilungspegel (Lr):" to "Lr = LAeq + KT + KI + KR — Beinhaltet normgerechte Zuschläge für Tonhaltigkeit (KT), Impulshaltigkeit (KI) und empfindliche Tageszeiten (KR).",
            "• Statistische Perzentilpegel (L1, L10, L50, L95):" to "L1 beschreibt die oberen Lärmspitzen (während 1% der Zeit überschritten), L50 den statistischen Median und L95 das verlässliche Hintergrund- und Grundgeräuschniveau.",
            "• Messunsicherheit & Kalibrierung:" to "Messgerät PCE-323 entspricht Klasse 2 nach IEC 61672-1 mit einer messtechnischen Geräteunsicherheit von ±1,4 dB. Kalibriert mit Frequenzbewertung dBA und Zeitbewertung Fast."
        )

        for ((title, desc) in formulas) {
            val tPaint = PdfCanvasExt.createPaint(PdfCanvasExt.COLOR_SECONDARY, textSize = 8.5f, isBold = true)
            canvas.drawText(title, PdfCanvasExt.MARGIN_LEFT, curY, tPaint)
            curY += 12f
            curY = PdfCanvasExt.drawParagraph(canvas, desc, PdfCanvasExt.MARGIN_LEFT + 10f, curY, PdfCanvasExt.CONTENT_WIDTH - 10f, bodyPaint, lineSpacingExtra = 2f)
            curY += 8f
        }

        curY += 10f
        // Richtwerte-Tabelle
        canvas.drawText("Übersicht der Immissionsrichtwerte (AVV Baulärm / TA Lärm)", PdfCanvasExt.MARGIN_LEFT, curY, sectionTitlePaint)
        curY += 12f

        val tableCols = listOf(160f, 110f, 110f, 135f)
        val headers = listOf("Gebietsart", "Tag (07–20h)", "Nacht (20–07h)", "Eingreifwert Tag")
        val rows = listOf(
            listOf("Reines Wohngebiet (WR)", "50 dB(A)", "35 dB(A)", "55 dB(A)"),
            listOf("Allgemeines Wohngebiet (WA)", "55 dB(A)", "40 dB(A)", "60 dB(A) [Maßgeblich]"),
            listOf("Mischgebiet / Kerngebiet (MI)", "60 dB(A)", "45 dB(A)", "65 dB(A)"),
            listOf("Gewerbegebiet (GE)", "65 dB(A)", "50 dB(A)", "70 dB(A)")
        )
        curY = PdfCanvasExt.drawTable(canvas, PdfCanvasExt.MARGIN_LEFT, curY, tableCols, headers, rows)

        PdfCanvasExt.drawFooter(canvas, pageNum, totalPages)
    }

    // ----------------------------------------------------
    // SEITE 4: KERNBEFUNDE (TABELLE ALLER MESSTAGE)
    // ----------------------------------------------------
    private fun renderKeyFindingsPage(canvas: Canvas, data: GesamtberichtData, pageNum: Int, totalPages: Int) {
        PdfCanvasExt.drawHeader(canvas, "Kernbefunde", "Übersicht und Kennwerte aller Messtage", pageNum)

        var curY = PdfCanvasExt.MARGIN_TOP + 36f

        val colWidths = listOf(65f, 40f, 50f, 48f, 48f, 52f, 55f, 55f, 102f)
        val headers = listOf("Datum", "Dauer", "Abdeck.", "LAeq", "Lr", "Lmax", "L1", "L95", "Status")
        val aligns = listOf(
            Paint.Align.LEFT, Paint.Align.RIGHT, Paint.Align.RIGHT, Paint.Align.RIGHT,
            Paint.Align.RIGHT, Paint.Align.RIGHT, Paint.Align.RIGHT, Paint.Align.RIGHT, Paint.Align.CENTER
        )

        val rows = data.tage.map { t ->
            listOf(
                t.dayKey,
                String.format(Locale.GERMAN, "%.1fh", t.gesamtdauerMs / 3600000.0),
                "${String.format(Locale.GERMAN, "%.0f", t.abdeckungTagPct * 100)}%",
                t.laeq?.let { String.format(Locale.GERMAN, "%.1f", it) } ?: "--",
                t.lr?.let { String.format(Locale.GERMAN, "%.1f", it) } ?: "--",
                t.lmax?.let { String.format(Locale.GERMAN, "%.1f", it) } ?: "--",
                t.l1?.let { String.format(Locale.GERMAN, "%.1f", it) } ?: "--",
                t.l95?.let { String.format(Locale.GERMAN, "%.1f", it) } ?: "--",
                if (t.grenzwertUeberschreitungTag) "Überschritten (>${data.config.richtwertTagWa.toInt()} dB)" else "Eingehalten"
            )
        }

        PdfCanvasExt.drawTable(canvas, PdfCanvasExt.MARGIN_LEFT, curY, colWidths, headers, rows, rowHeight = 15f, alignments = aligns)

        PdfCanvasExt.drawFooter(canvas, pageNum, totalPages)
    }

    // ----------------------------------------------------
    // SEITE 5: BELASTUNGSDAUER & LAUTESTE STUNDE
    // ----------------------------------------------------
    private fun renderExposurePage(canvas: Canvas, data: GesamtberichtData, pageNum: Int, totalPages: Int) {
        PdfCanvasExt.drawHeader(canvas, "Belastungsanalyse", "Belastungsdauer nach Pegelklassen & Lauteste Stunde", pageNum)

        var curY = PdfCanvasExt.MARGIN_TOP + 36f
        val secTitlePaint = PdfCanvasExt.createPaint(PdfCanvasExt.COLOR_PRIMARY, textSize = 10.5f, isBold = true)

        canvas.drawText("1. Verteilung der täglichen Messzeit auf Pegelbänder", PdfCanvasExt.MARGIN_LEFT, curY, secTitlePaint)
        curY += 12f

        // Balkendiagramm
        val chartHeight = 220f
        curY = ChartRenderer.drawExposureBarChart(canvas, PdfCanvasExt.MARGIN_LEFT, curY, PdfCanvasExt.CONTENT_WIDTH, chartHeight, data.tage)

        curY += 20f
        canvas.drawText("2. Analyse der lautesten 60-Minuten-Intervalle (Lauteste Stunde)", PdfCanvasExt.MARGIN_LEFT, curY, secTitlePaint)
        curY += 12f

        val tableCols = listOf(80f, 75f, 75f, 130f, 155f)
        val headers = listOf("Datum", "Startzeit", "LAeq, 1h", "Differenz zu WA-Richtwert", "Bewertung")
        val aligns = listOf(Paint.Align.LEFT, Paint.Align.CENTER, Paint.Align.RIGHT, Paint.Align.RIGHT, Paint.Align.LEFT)

        val rows = data.tage.take(12).map { t ->
            val diff = (t.lautesteStundeLeq ?: 0.0) - data.config.richtwertTagWa
            val diffStr = if (diff > 0) "+${String.format(Locale.GERMAN, "%.1f dB", diff)}" else "${String.format(Locale.GERMAN, "%.1f dB", diff)}"
            listOf(
                t.dayKey,
                t.lautesteStundeStart ?: "–",
                t.lautesteStundeLeq?.let { String.format(Locale.GERMAN, "%.1f dB(A)", it) } ?: "–",
                diffStr,
                if (diff > 5.0) "Eingreifwert massiv überschritten" else if (diff > 0) "Richtwert überschritten" else "Im zulässigen Rahmen"
            )
        }

        PdfCanvasExt.drawTable(canvas, PdfCanvasExt.MARGIN_LEFT, curY, tableCols, headers, rows, rowHeight = 15f, alignments = aligns)

        PdfCanvasExt.drawFooter(canvas, pageNum, totalPages)
    }

    // ----------------------------------------------------
    // SEITEN 6+: EINZELSEITE JE MESSTAG
    // ----------------------------------------------------
    private fun renderDailyPage(canvas: Canvas, tag: TagAuswertung, config: com.example.lrmprotokoll.report.gesamtbericht.model.GesamtberichtConfig, pageNum: Int, totalPages: Int) {
        val dateFormatted = SimpleDateFormat("EEEE, dd. MMMM yyyy", Locale.GERMAN).format(Date(tag.timestamp))
        PdfCanvasExt.drawHeader(canvas, "Tagesprotokoll", "$dateFormatted (${tag.dayKey})", pageNum)

        var curY = PdfCanvasExt.MARGIN_TOP + 36f

        // 1. Pegelverlaufsdiagramm (Groß)
        val chartH = 210f
        ChartRenderer.drawDayTimeSeriesChart(
            canvas = canvas,
            x = PdfCanvasExt.MARGIN_LEFT,
            y = curY,
            width = PdfCanvasExt.CONTENT_WIDTH,
            height = chartH,
            auswertung = tag,
            richtwertTag = config.richtwertTagWa,
            eingreifwertTag = config.eingreifwertTag
        )
        curY += chartH + 16f

        // 2. Akustische Kennwerte des Tages
        val colWidths = listOf(65f, 65f, 65f, 65f, 65f, 65f, 65f, 60f)
        val headers = listOf("LAeq", "Lr", "Lmax", "L1", "L10", "L50", "L95", "Dauer")
        val aligns = listOf(Paint.Align.CENTER, Paint.Align.CENTER, Paint.Align.CENTER, Paint.Align.CENTER, Paint.Align.CENTER, Paint.Align.CENTER, Paint.Align.CENTER, Paint.Align.CENTER)

        val rows = listOf(
            listOf(
                tag.laeq?.let { String.format(Locale.GERMAN, "%.1f dB", it) } ?: "--",
                tag.lr?.let { String.format(Locale.GERMAN, "%.1f dB", it) } ?: "--",
                tag.lmax?.let { String.format(Locale.GERMAN, "%.1f dB", it) } ?: "--",
                tag.l1?.let { String.format(Locale.GERMAN, "%.1f dB", it) } ?: "--",
                tag.l10?.let { String.format(Locale.GERMAN, "%.1f dB", it) } ?: "--",
                tag.l50?.let { String.format(Locale.GERMAN, "%.1f dB", it) } ?: "--",
                tag.l95?.let { String.format(Locale.GERMAN, "%.1f dB", it) } ?: "--",
                String.format(Locale.GERMAN, "%.1fh", tag.gesamtdauerMs / 3600000.0)
            )
        )
        curY = PdfCanvasExt.drawTable(canvas, PdfCanvasExt.MARGIN_LEFT, curY, colWidths, headers, rows, rowHeight = 16f, alignments = aligns)

        curY += 16f

        // 3. Ereignisliste / Lärmquellenverteilung
        val secTitlePaint = PdfCanvasExt.createPaint(PdfCanvasExt.COLOR_PRIMARY, textSize = 10f, isBold = true)
        canvas.drawText("Dokumentierte Lärmereignisse & Überschreitungen (${tag.ereignisse.size})", PdfCanvasExt.MARGIN_LEFT, curY, secTitlePaint)
        curY += 10f

        if (tag.ereignisse.isEmpty()) {
            val emptyPaint = PdfCanvasExt.createPaint(PdfCanvasExt.COLOR_TEXT_MUTED, textSize = 8f)
            canvas.drawText("Keine manuellen oder automatischen Schwellenüberschreitungen für diesen Tag verzeichnet.", PdfCanvasExt.MARGIN_LEFT, curY + 10f, emptyPaint)
        } else {
            val evCols = listOf(50f, 65f, 130f, 130f, 140f)
            val evHeaders = listOf("Zeit", "Pegel", "Geräuschquelle", "KI-Klassifikation", "Hinweis")
            val evAligns = listOf(Paint.Align.LEFT, Paint.Align.RIGHT, Paint.Align.LEFT, Paint.Align.LEFT, Paint.Align.LEFT)
            val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.GERMAN)

            val evRows = tag.ereignisse.take(14).map { r ->
                val pegelStr = r.calibratedDbA?.let { String.format(Locale.GERMAN, "%.1f dBA", it) } ?: "${String.format(Locale.GERMAN, "%.1f", r.dbValue)} dB"
                listOf(
                    timeFmt.format(Date(r.timestamp)),
                    pegelStr,
                    r.label ?: "–",
                    r.detectedLabel ?: "–",
                    if (r.isQuietHour) "Ruhezeit-Verstoß" else "Schwellenüberschreitung"
                )
            }
            PdfCanvasExt.drawTable(canvas, PdfCanvasExt.MARGIN_LEFT, curY, evCols, evHeaders, evRows, rowHeight = 14f, alignments = evAligns)
        }

        PdfCanvasExt.drawFooter(canvas, pageNum, totalPages)
    }

    // ----------------------------------------------------
    // LETZTE SEITE: ROHDATEN-MANIFEST (SHA-256)
    // ----------------------------------------------------
    private fun renderManifestPage(canvas: Canvas, data: GesamtberichtData, pageNum: Int, totalPages: Int) {
        PdfCanvasExt.drawHeader(canvas, "Audit-Trail", "Revisionssicheres Rohdaten-Manifest (SHA-256 Prüfsummen)", pageNum)

        var curY = PdfCanvasExt.MARGIN_TOP + 36f

        val noteText = "Gemäß Grundsätzen revisionssicherer Messdatenerfassung ist jeder Datensatz mit einem unveränderlichen SHA-256 kryptografischen Hash versehen. Dies belegt die Authentizität und Unverfälschtheit der Rohdaten gegenüber Gerichten, Sachverständigen und Immissionsschutzbehörden."
        val bodyPaint = PdfCanvasExt.createPaint(PdfCanvasExt.COLOR_TEXT_MAIN, textSize = 8f)
        curY = PdfCanvasExt.drawParagraph(canvas, noteText, PdfCanvasExt.MARGIN_LEFT, curY, PdfCanvasExt.CONTENT_WIDTH, bodyPaint, lineSpacingExtra = 2.5f)

        curY += 14f

        val colWidths = listOf(60f, 100f, 130f, 45f, 180f)
        val headers = listOf("Datum", "Typ", "Dateiname / Quelle", "Größe", "SHA-256 Prüfsumme")
        val aligns = listOf(Paint.Align.LEFT, Paint.Align.LEFT, Paint.Align.LEFT, Paint.Align.RIGHT, Paint.Align.LEFT)

        val rows = data.manifest.take(30).map { m ->
            val sizeStr = if (m.bytes > 1024 * 1024) "${m.bytes / (1024 * 1024)} MB" else "${m.bytes / 1024} KB"
            val shortHash = if (m.sha256.length > 24) m.sha256.substring(0, 24) + "…" else m.sha256
            listOf(
                m.datum,
                m.typ,
                m.dateiName,
                sizeStr,
                shortHash
            )
        }

        PdfCanvasExt.drawTable(canvas, PdfCanvasExt.MARGIN_LEFT, curY, colWidths, headers, rows, rowHeight = 14f, alignments = aligns)

        PdfCanvasExt.drawFooter(canvas, pageNum, totalPages)
    }
}
