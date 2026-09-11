package com.example.lrmprotokoll.report

import com.example.lrmprotokoll.messreihe.AkustischeKennwerte
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [berechneGesamtDatenverfuegbarkeit] ist die einzige Logik in [GesamtberichtExport], die ohne
 * Canvas/PdfDocument testbar ist - der Rest der Klasse zeichnet nur (siehe KDoc von
 * [com.example.lrmprotokoll.report.pdf.BerichtSeiten]: PdfDocument.startPage() wirft unter
 * Robolectric zuverlaessig eine IllegalStateException).
 */
class GesamtberichtExportTest {

    private fun leereKennwerte() = AkustischeKennwerte.Kennwerte(
        leqDb = null, maxDb = null, minDb = null, l10Db = null, l50Db = null, l90Db = null,
        ueberschreitungsdauerMs = 0L, sampleCount = 0,
    )

    private fun tag(von: Long, bis: Long, datenverfuegbarkeitProzent: Double) = GesamtberichtTag(
        von = von,
        bis = bis,
        bericht = PeriodenBericht(
            von = von, bis = bis, sessionCount = 0,
            chartSpalten = emptyList(), kennwerte = leereKennwerte(),
            ausfallbaender = emptyList(), events = emptyList(), nurMikrofon = false,
        ),
        datenverfuegbarkeitProzent = datenverfuegbarkeitProzent,
    )

    @Test
    fun ohneTageNullProzent() {
        val bericht = Gesamtbericht(
            gesamt = PeriodenBericht(
                von = 0L, bis = 0L, sessionCount = 0, chartSpalten = emptyList(),
                kennwerte = leereKennwerte(), ausfallbaender = emptyList(), events = emptyList(),
                nurMikrofon = false,
            ),
            tage = emptyList(),
        )
        assertEquals(0.0, berechneGesamtDatenverfuegbarkeit(bericht), 0.001)
    }

    @Test
    fun gleichLangeTageWerdenSchlichtGemittelt() {
        val bericht = Gesamtbericht(
            gesamt = tag(0L, 2 * 86_400_000L, 0.0).bericht,
            tage = listOf(
                tag(0L, 86_400_000L, 100.0),
                tag(86_400_000L, 2 * 86_400_000L, 50.0),
            ),
        )
        assertEquals(75.0, berechneGesamtDatenverfuegbarkeit(bericht), 0.001)
    }

    @Test
    fun unterschiedlichLangeTageWerdenNachDauerGewichtet() {
        // Erster Tag (kurz, 1 h): 0 % verfuegbar. Zweiter Tag (lang, 9 h): 100 % verfuegbar.
        // Ein ungewichteter Mittelwert waere 50 %, der gewichtete liegt deutlich naeher an 100 %.
        val kurzerTag = tag(0L, 3_600_000L, 0.0)
        val langerTag = tag(3_600_000L, 3_600_000L + 9 * 3_600_000L, 100.0)
        val bericht = Gesamtbericht(
            gesamt = kurzerTag.bericht,
            tage = listOf(kurzerTag, langerTag),
        )
        assertEquals(90.0, berechneGesamtDatenverfuegbarkeit(bericht), 0.001)
    }
}
