package com.example.lrmprotokoll.report.pdf

import android.graphics.Canvas
import com.example.lrmprotokoll.report.TagesberichtDaten
import com.example.lrmprotokoll.report.TagesberichtZeile
import com.example.lrmprotokoll.report.formatiere
import java.io.File

/**
 * Rendert einen [TagesberichtDaten] nach PDF. Enthaelt bewusst KEINE Fachlogik - was im Bericht
 * steht, entscheidet [com.example.lrmprotokoll.report.ermittleTagesbericht]; diese Datei
 * entscheidet nur, wo es auf dem Blatt steht.
 *
 * Die Seitenmechanik steckt in [BerichtSeiten]; hier steht nur, was wo auf dem Blatt landet.
 */
object TagesberichtPdf {

    private const val ZEILEN_HOEHE = 16f
    private val SPALTEN = listOf(70f, 150f, 120f, 120f, 55f)
    private val TITEL = listOf("Zeit", "Pegel", "Bezeichnung", "KI-Erkennung", "Ruhezeit")

    fun schreibe(daten: TagesberichtDaten, ziel: File): File = BerichtSeiten.schreibe(
        ziel = ziel,
        abschnitt = "Tagesbericht",
        fussHinweis = "Lärmprotokoll – Tagesbericht ${daten.datum}",
    ) { lauf, canvasGeber -> baueAuf(lauf, daten, canvasGeber) }

    /**
     * Der Seitenaufbau. Wird zweimal durchlaufen; [seiteGeber] liefert im Zeichendurchlauf den
     * Canvas der gerade offenen Seite und im Zaehldurchlauf `null`.
     *
     * Wichtig fuer die Korrektheit der Seitenzahl: Der y-Verlauf haengt ausschliesslich an
     * [Seitenlauf], nie am Canvas - beide Durchlaeufe brechen deshalb an denselben Stellen um.
     */
    private fun baueAuf(
        lauf: Seitenlauf,
        daten: TagesberichtDaten,
        seiteGeber: () -> Canvas?,
    ) {
        val x = Seitenlauf.RAND_LINKS
        val breite = Seitenlauf.INHALT_BREITE

        val titelPaint = BerichtLayout.paint(BerichtLayout.COLOR_PRIMARY, textSize = 16f, fett = true)
        val textPaint = BerichtLayout.paint(textSize = 10f)
        val kleinPaint = BerichtLayout.paint(BerichtLayout.COLOR_TEXT_MUTED, textSize = 9f)

        var y = lauf.platziere(28f)
        seiteGeber()?.drawText("Tagesbericht ${daten.datum}", x, y + 14f, titelPaint)

        y = lauf.platziere(16f)
        seiteGeber()?.drawText("Messgerät: ${daten.geraet}", x, y + 10f, kleinPaint)
        lauf.abstand(10f)

        // Der Hinweis auf unbestaetigte Frequenzbewertungen steht bewusst VOR den Kennzahlen -
        // er relativiert sie.
        daten.bewertungsHinweis?.let { hinweis ->
            val hoehe = BerichtLayout.absatzHoehe(hinweis, breite - 20f, kleinPaint) + 18f
            val boxY = lauf.platziere(hoehe + 8f)
            seiteGeber()?.let { c ->
                BerichtLayout.karte(
                    c, x, boxY, breite, hoehe,
                    fuellFarbe = BerichtLayout.COLOR_WARN_BG, rahmenFarbe = BerichtLayout.COLOR_WARN,
                )
                BerichtLayout.absatz(c, hinweis, x + 10f, boxY + 10f, breite - 20f, kleinPaint)
            }
            lauf.abstand(8f)
        }

        listOf(
            "Gesamtzahl Ereignisse: ${daten.anzahlEreignisse}",
            "Spitzenpegel: ${formatiere(daten.spitzenpegel)} dB",
            "Ereignisse in Ruhezeiten: ${daten.anzahlRuhezeit}",
        ).forEach { zeile ->
            val zy = lauf.platziere(15f)
            seiteGeber()?.drawText(zeile, x, zy + 10f, textPaint)
        }
        lauf.abstand(14f)

        if (daten.zeilen.isEmpty()) {
            val zy = lauf.platziere(15f)
            seiteGeber()?.drawText("Keine Ereignisse an diesem Tag.", x, zy + 10f, textPaint)
            return
        }

        var kopfY = lauf.platziere(ZEILEN_HOEHE)
        seiteGeber()?.let { BerichtLayout.tabellenKopf(it, x, kopfY, SPALTEN, TITEL, ZEILEN_HOEHE) }
        var letzteSeite = lauf.seitenNummer

        daten.zeilen.forEachIndexed { index, zeile ->
            val zy = lauf.platziere(ZEILEN_HOEHE)
            // Nach einem Seitenumbruch die Tabellenkopfzeile wiederholen - ohne sie ist eine
            // Folgeseite eine Spaltenwueste ohne Beschriftung.
            if (lauf.seitenNummer != letzteSeite) {
                letzteSeite = lauf.seitenNummer
                kopfY = zy
                seiteGeber()?.let { BerichtLayout.tabellenKopf(it, x, kopfY, SPALTEN, TITEL, ZEILEN_HOEHE) }
                val neueZeileY = lauf.platziere(ZEILEN_HOEHE)
                seiteGeber()?.let { zeichneZeile(it, x, neueZeileY, zeile, index) }
            } else {
                seiteGeber()?.let { zeichneZeile(it, x, zy, zeile, index) }
            }
        }
    }

    private fun zeichneZeile(canvas: Canvas, x: Float, y: Float, zeile: TagesberichtZeile, index: Int) {
        BerichtLayout.tabellenZeile(
            canvas, x, y, SPALTEN,
            listOf(
                zeile.uhrzeit,
                zeile.pegel,
                zeile.label ?: "–",
                zeile.kiLabel ?: "–",
                if (zeile.inRuhezeit) "ja" else "–",
            ),
            ZEILEN_HOEHE,
            geradeZeile = index % 2 == 1,
        )
    }
}
