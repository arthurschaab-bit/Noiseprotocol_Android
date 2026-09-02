package com.example.lrmprotokoll.report.pdf

import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream

/**
 * Erzeugt ein mehrseitiges Berichts-PDF aus einem Aufbau, der zweimal durchlaufen wird: einmal
 * zum Zaehlen der Seiten (der Canvas-Geber liefert `null`), einmal zum Zeichnen.
 *
 * Genau eine Stelle im Projekt kennt damit noch `PdfDocument`, `startPage`/`finishPage` und die
 * Reihenfolge, in der Kopf- und Fusszeile gesetzt werden muessen. Vorher hatten
 * [com.example.lrmprotokoll.report.MessreiheExport] und
 * [com.example.lrmprotokoll.report.PeriodenBerichtExport] je eine eigene Kopie davon - beide
 * einseitig, beide mit stillem Abriss am unteren Blattrand.
 *
 * Der Aufbau bekommt den [Seitenlauf] und einen Canvas-Geber. Er darf den Canvas NUR ueber den
 * Geber holen und niemals zwischenspeichern: Nach jedem [Seitenlauf.platziere] kann eine neue
 * Seite offen sein, und ein gemerkter Canvas wuerde dann auf die bereits geschlossene Vorseite
 * zeichnen.
 */
object BerichtSeiten {

    fun schreibe(
        ziel: File,
        abschnitt: String,
        fussHinweis: String = "",
        aufbau: (Seitenlauf, () -> Canvas?) -> Unit,
    ): File {
        val gesamtSeiten = Seitenlauf.zaehleSeiten { lauf -> aufbau(lauf) { null } }

        val dokument = PdfDocument()
        var offeneSeite: PdfDocument.Page? = null

        val lauf = Seitenlauf(onNeueSeite = { nummer ->
            offeneSeite?.let { dokument.finishPage(it) }
            val neue = dokument.startPage(
                PdfDocument.PageInfo.Builder(
                    Seitenlauf.SEITE_BREITE.toInt(),
                    Seitenlauf.SEITE_HOEHE.toInt(),
                    nummer,
                ).create()
            )
            offeneSeite = neue
            BerichtLayout.kopfzeile(neue.canvas, abschnitt)
            BerichtLayout.fusszeile(neue.canvas, nummer, gesamtSeiten, fussHinweis)
        })

        aufbau(lauf) { offeneSeite?.canvas }
        offeneSeite?.let { dokument.finishPage(it) }

        FileOutputStream(ziel).use { dokument.writeTo(it) }
        dokument.close()
        return ziel
    }
}
