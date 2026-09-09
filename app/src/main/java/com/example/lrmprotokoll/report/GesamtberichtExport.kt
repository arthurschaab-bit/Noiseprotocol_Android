package com.example.lrmprotokoll.report

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import com.example.lrmprotokoll.report.pdf.BerichtLayout
import com.example.lrmprotokoll.report.pdf.BerichtSeiten
import com.example.lrmprotokoll.report.pdf.Seitenlauf
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PDF-Export eines [Gesamtbericht] - die vollstaendige Rechtsdokumentation mit Geraete-,
 * Messaufbau- und Randbedingungsangaben (Owner-Anfrage 09.09.2026, Vorbild: die vom Owner
 * gelieferte Referenz-PDF einer separaten Python-Pipeline). Anders als [PeriodenBerichtExport]
 * (Kennwerte + EIN Diagramm ueber den Gesamtzeitraum) zusaetzlich: ein Deckblatt mit den
 * Stammdaten aus den Einstellungen, eine Tagesuebersichtstabelle und eine eigene Seite je Messtag.
 *
 * Rechnet und zeichnet NICHTS doppelt: Kennwerte kommen aus [PeriodenBericht]/
 * [AkustischeKennwerte] (ueber [Gesamtbericht]/[ermittleGesamtbericht]), die Pegelkurven aus dem
 * bereits vorhandenen [zeichnePegelverlaufChart] (jetzt `internal`, siehe dessen KDoc) - genau die
 * Vermeidung eines zweiten Berechnungs-/Zeichenpfads neben dem bestehenden, wegen der PR #78
 * abgelehnt wurde (siehe KDoc von [BerichtLayout]).
 *
 * Enthaelt bewusst KEINE Rechtsanalyse (AVV Baulaerm-Grenzwertvergleich, §34 BauGB o.ae.): Welcher
 * Massstab im Einzelfall gilt, ist eine juristische Einschaetzung, die die App nicht treffen kann,
 * ohne im Zweifel eine falsche rechtliche Aussage zu drucken. Die App liefert Messdaten und die
 * vom Nutzer eingetragenen Stammdaten - die rechtliche Wuerdigung bleibt aussen vor.
 */
class GesamtberichtExport(private val context: Context) {

    private val formatierer = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    private val tagFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
    private val tagKurzFormat = SimpleDateFormat("dd.MM. (EEEE)", Locale.getDefault())

    fun exportierePdf(bericht: Gesamtbericht, stammdaten: GesamtberichtStammdaten, titel: String): File {
        val gesamt = bericht.gesamt
        val kennwerte = gesamt.kennwerte
        val gesamtDatenverfuegbarkeit = berechneGesamtDatenverfuegbarkeit(bericht)
        val istInnenraumRelevant = stammdaten.innenAussen.contains("innen", ignoreCase = true)

        val aufbau: (Seitenlauf, () -> Canvas?) -> Unit = { lauf, canvasGeber ->
            val x = Seitenlauf.RAND_LINKS
            val breite = Seitenlauf.INHALT_BREITE
            val rechtsrand = Seitenlauf.SEITE_BREITE - Seitenlauf.RAND_RECHTS
            val titelPaint = BerichtLayout.paint(BerichtLayout.COLOR_PRIMARY, textSize = 18f, fett = true)
            val kopfPaint = BerichtLayout.paint(BerichtLayout.COLOR_PRIMARY, textSize = 13f, fett = true)
            val textPaint = BerichtLayout.paint(textSize = 10.5f)
            val mutedPaint = BerichtLayout.paint(BerichtLayout.COLOR_TEXT_MUTED, textSize = 9f)

            fun zeile(inhalt: String, paint: Paint = textPaint, hoehe: Float = 16f) {
                val y = lauf.platziere(hoehe)
                canvasGeber()?.drawText(inhalt, x, y + hoehe - 4f, paint)
            }

            fun abschnitt(titel: String) {
                lauf.abstand(6f)
                zeile(titel, kopfPaint, 22f)
            }

            fun feld(label: String, wert: String) = zeile("$label: ${wert.oderNichtAngegeben()}")

            // ---- Deckblatt ----
            zeile(titel, titelPaint, 26f)
            zeile("Zeitraum: ${formatierer.format(Date(gesamt.von))} – ${formatierer.format(Date(gesamt.bis))}")
            zeile("${bericht.tage.size} Messtag(e), ${gesamt.sessionCount} Session(en), ${kennwerte.sampleCount} Messwerte")
            feld("Messort", stammdaten.messort)
            feld("Gerät", geraeteAnzeige(stammdaten))
            feld("Datenverfügbarkeit im Zeitraum", "%.1f %%".format(Locale.getDefault(), gesamtDatenverfuegbarkeit))

            // ---- Gerät und Kalibrierung ----
            abschnitt("Gerät und Kalibrierung")
            feld("Hersteller", stammdaten.geraetHersteller)
            feld("Typ", stammdaten.geraetTyp)
            feld("Genauigkeitsklasse", stammdaten.geraetGenauigkeitsklasse)
            feld("Seriennummer", stammdaten.geraetSeriennummer)
            feld("Kalibrierung", stammdaten.geraetKalibrierung)

            // ---- Messaufbau ----
            abschnitt("Messaufbau")
            feld("Messort", stammdaten.messort)
            feld("Mikrofonposition", stammdaten.mikrofonposition)
            feld("Mikrofonhöhe", stammdaten.mikrofonhoehe)
            feld("Entfernung Mikrofon–Quelle", stammdaten.entfernungZurQuelle)
            feld("Innen-/Außenmessung", stammdaten.innenAussen)
            if (istInnenraumRelevant) {
                feld("Fenster", stammdaten.fensterzustand)
            }

            // ---- Randbedingungen ----
            abschnitt("Randbedingungen")
            feld("Wetter", stammdaten.wetter)
            zeile("Datenverfügbarkeit: %.1f %% (aus Verbindungsausfällen berechnet)".format(
                Locale.getDefault(), gesamtDatenverfuegbarkeit,
            ))
            if (stammdaten.datenqualitaetHinweis.isNotBlank()) {
                zeile(stammdaten.datenqualitaetHinweis)
            }

            // ---- Kernergebnisse ----
            abschnitt("Kernergebnisse (Gesamtzeitraum)")
            listOfNotNull(
                kennwerte.leqDb?.let { "LAeq: ${formatiereDb(it)} dB" },
                kennwerte.maxDb?.let { "Max: ${formatiereDb(it)} dB" },
                kennwerte.minDb?.let { "Min: ${formatiereDb(it)} dB" },
                kennwerte.l10Db?.let { "L10: ${formatiereDb(it)} dB" },
                kennwerte.l50Db?.let { "L50: ${formatiereDb(it)} dB" },
                kennwerte.l90Db?.let { "L90: ${formatiereDb(it)} dB" },
            ).ifEmpty { listOf("Für diesen Zeitraum liegen keine Messwerte vor.") }.forEach { zeile(it) }

            // ---- Pegelverlauf (Gesamtzeitraum) ----
            abschnitt("Pegelverlauf (Gesamtzeitraum)")
            val chartHeight = 200f
            val chartTop = lauf.platziere(chartHeight)
            canvasGeber()?.let { c ->
                zeichnePegelverlaufChart(
                    canvas = c,
                    spalten = gesamt.chartSpalten,
                    ausfallbaender = gesamt.ausfallbaender,
                    events = gesamt.events,
                    von = gesamt.von,
                    bis = gesamt.bis,
                    laeqDb = kennwerte.leqDb,
                    left = x,
                    top = chartTop,
                    right = rechtsrand,
                    bottom = chartTop + chartHeight,
                )
            }

            // ---- Tagesübersicht ----
            lauf.neueSeite()
            zeile("Tagesübersicht — alle Messtage", kopfPaint, 22f)
            if (bericht.tage.isEmpty()) {
                zeile("Keine Messtage in diesem Zeitraum.")
            } else {
                zeichneTagesuebersicht(lauf, canvasGeber, bericht.tage)
            }

            // ---- Tagesseiten ----
            bericht.tage.forEach { tag -> zeichneTagesseite(lauf, canvasGeber, tag) }

            // ---- Ausfälle (vollständig) ----
            lauf.neueSeite()
            zeile("Ausfälle (${gesamt.ausfallbaender.size})", kopfPaint, 22f)
            if (gesamt.ausfallbaender.isEmpty()) {
                zeile("Keine Verbindungsausfälle in diesem Zeitraum.")
            } else {
                gesamt.ausfallbaender.forEach { band ->
                    val ende = band.bis?.let { formatierer.format(Date(it)) } ?: "andauernd"
                    zeile("${formatierer.format(Date(band.von))} – $ende")
                }
            }
            lauf.abstand(10f)

            // ---- Ereignisse (vollständig) ----
            zeile("Ereignisse (${gesamt.events.size})", kopfPaint, 22f)
            if (gesamt.events.isEmpty()) {
                zeile("Keine markierten Ereignisse in diesem Zeitraum.")
            } else {
                gesamt.events.forEach { event ->
                    val pegel = event.calibratedDbA ?: event.dbValue
                    val beschriftung = event.label ?: event.detectedLabel ?: "Ereignis"
                    zeile("${formatierer.format(Date(event.timestamp))} – $beschriftung (${formatiereDb(pegel)} dB)")
                }
            }
        }

        val dateiname = "Gesamtbericht_${tagFormat.format(Date(gesamt.von))}-${tagFormat.format(Date(gesamt.bis))}.pdf"
        val datei = File(BerichtDatei.ordner(context), dateiname)
        BerichtSeiten.schreibe(datei, abschnitt = "Gesamtbericht", fussHinweis = titel, aufbau = aufbau)
        return datei
    }

    private val tabellenSpalten = listOf(90f, 70f, 70f, 90f, 70f)
    private val tabellenTitel = listOf("Tag", "LAeq", "Max", "Verfügbarkeit", "Ausfälle")
    private val tabellenZeilenHoehe = 16f

    private fun zeichneTagesuebersicht(
        lauf: Seitenlauf,
        canvasGeber: () -> Canvas?,
        tage: List<GesamtberichtTag>,
    ) {
        val x = Seitenlauf.RAND_LINKS
        var kopfY = lauf.platziere(tabellenZeilenHoehe)
        canvasGeber()?.let { BerichtLayout.tabellenKopf(it, x, kopfY, tabellenSpalten, tabellenTitel, tabellenZeilenHoehe) }
        var letzteSeite = lauf.seitenNummer

        tage.forEachIndexed { index, tag ->
            val zy = lauf.platziere(tabellenZeilenHoehe)
            val werte = listOf(
                tagFormat.format(Date(tag.von)),
                tag.bericht.kennwerte.leqDb?.let { "${formatiereDb(it)} dB" } ?: "–",
                tag.bericht.kennwerte.maxDb?.let { "${formatiereDb(it)} dB" } ?: "–",
                "%.0f %%".format(Locale.getDefault(), tag.datenverfuegbarkeitProzent),
                "${tag.bericht.ausfallbaender.size}",
            )
            // Nach einem Seitenumbruch die Tabellenkopfzeile wiederholen (dasselbe Muster wie
            // TagesberichtPdf) - ohne sie ist eine Folgeseite eine Spaltenwueste.
            if (lauf.seitenNummer != letzteSeite) {
                letzteSeite = lauf.seitenNummer
                kopfY = zy
                canvasGeber()?.let { BerichtLayout.tabellenKopf(it, x, kopfY, tabellenSpalten, tabellenTitel, tabellenZeilenHoehe) }
                val neueZeileY = lauf.platziere(tabellenZeilenHoehe)
                canvasGeber()?.let { BerichtLayout.tabellenZeile(it, x, neueZeileY, tabellenSpalten, werte, tabellenZeilenHoehe, index % 2 == 1) }
            } else {
                canvasGeber()?.let { BerichtLayout.tabellenZeile(it, x, zy, tabellenSpalten, werte, tabellenZeilenHoehe, index % 2 == 1) }
            }
        }
    }

    private fun zeichneTagesseite(lauf: Seitenlauf, canvasGeber: () -> Canvas?, tag: GesamtberichtTag) {
        val x = Seitenlauf.RAND_LINKS
        val rechtsrand = Seitenlauf.SEITE_BREITE - Seitenlauf.RAND_RECHTS
        val kopfPaint = BerichtLayout.paint(BerichtLayout.COLOR_PRIMARY, textSize = 13f, fett = true)
        val textPaint = BerichtLayout.paint(textSize = 10.5f)

        lauf.neueSeite()
        val ty = lauf.platziere(22f)
        canvasGeber()?.drawText(tagKurzFormat.format(Date(tag.von)), x, ty + 16f, kopfPaint)

        val kennwerte = tag.bericht.kennwerte
        val zy = lauf.platziere(16f)
        val kennwertZeile = listOfNotNull(
            kennwerte.leqDb?.let { "LAeq ${formatiereDb(it)} dB" },
            kennwerte.maxDb?.let { "Max ${formatiereDb(it)} dB" },
            kennwerte.minDb?.let { "Min ${formatiereDb(it)} dB" },
        ).joinToString("  ·  ").ifEmpty { "Keine Messwerte" } +
            "  ·  Datenverfügbarkeit %.0f %%".format(Locale.getDefault(), tag.datenverfuegbarkeitProzent)
        canvasGeber()?.drawText(kennwertZeile, x, zy + 11f, textPaint)
        lauf.abstand(8f)

        val chartHeight = 200f
        val chartTop = lauf.platziere(chartHeight)
        canvasGeber()?.let { c ->
            zeichnePegelverlaufChart(
                canvas = c,
                spalten = tag.bericht.chartSpalten,
                ausfallbaender = tag.bericht.ausfallbaender,
                events = tag.bericht.events,
                von = tag.von,
                bis = tag.bis,
                laeqDb = kennwerte.leqDb,
                left = x,
                top = chartTop,
                right = rechtsrand,
                bottom = chartTop + chartHeight,
            )
        }
    }

    private fun geraeteAnzeige(stammdaten: GesamtberichtStammdaten): String {
        val teile = listOf(stammdaten.geraetHersteller, stammdaten.geraetTyp).filter { it.isNotBlank() }
        val kern = if (teile.isEmpty()) "nicht angegeben" else teile.joinToString(" ")
        return if (stammdaten.geraetGenauigkeitsklasse.isNotBlank()) {
            "$kern, ${stammdaten.geraetGenauigkeitsklasse}"
        } else {
            kern
        }
    }

    private fun formatiereDb(wert: Double) = String.format(Locale.getDefault(), "%.1f", wert)

    private fun String.oderNichtAngegeben() = ifBlank { "nicht angegeben" }

    fun teilen(file: File) = BerichtDatei.teile(context, file)
}

/**
 * Gewichteter Durchschnitt der Tages-Datenverfuegbarkeiten ueber ihre jeweilige Dauer - eine
 * einfache mittlere Prozentzahl ueber alle Tage waere bei unterschiedlich langen Tagen (Anfangs-/
 * Endtag eines Zeitraums oft kuerzer) leicht irrefuehrend.
 */
internal fun berechneGesamtDatenverfuegbarkeit(bericht: Gesamtbericht): Double {
    if (bericht.tage.isEmpty()) return 0.0
    val gesamtDauer = bericht.tage.sumOf { it.bis - it.von }
    if (gesamtDauer <= 0L) return 0.0
    val gewichteteSumme = bericht.tage.sumOf { tag -> tag.datenverfuegbarkeitProzent * (tag.bis - tag.von) }
    return gewichteteSumme / gesamtDauer
}
