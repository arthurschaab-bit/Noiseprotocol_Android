package com.example.lrmprotokoll.report

import android.graphics.Canvas
import android.graphics.Paint
import com.example.lrmprotokoll.messreihe.ChartSpalte
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review PR #275: Eine einzelne Mikrofon-Chartspalte (oder ein isolierter Punkt zwischen Luecken)
 * darf nicht verschwinden - der Bericht zeigte sonst Achsen und Legende, aber keinen Messpunkt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MikrofonPunktChartTest {
    private class AufzeichnendeLeinwand : Canvas() {
        val kreisRadien = mutableListOf<Float>()

        override fun drawCircle(
            cx: Float,
            cy: Float,
            radius: Float,
            paint: Paint,
        ) {
            kreisRadien += radius
        }
    }

    private fun zeichne(
        spalten: List<ChartSpalte>,
        mikrofon: List<ChartSpalte>,
    ): List<Float> {
        val leinwand = AufzeichnendeLeinwand()
        zeichnePegelverlaufChart(
            canvas = leinwand,
            spalten = spalten,
            ausfallbaender = emptyList(),
            events = emptyList(),
            von = 0L,
            bis = 100_000L,
            laeqDb = null,
            left = 0f,
            top = 0f,
            right = 300f,
            bottom = 200f,
            mikrofonSpalten = mikrofon,
        )
        return leinwand.kreisRadien
    }

    private fun spalte(sekunden: Long) = ChartSpalte(sekunden, 70.0, 70.0, 70.0, 1)

    @Test
    fun einzelneMikrofonSpalteOhnePceKurveWirdAlsPunktGezeichnet() {
        val radien = zeichne(spalten = emptyList(), mikrofon = listOf(spalte(50)))
        assertEquals(listOf(MIKROFON_PUNKT_RADIUS), radien)
    }

    @Test
    fun isolierterMikrofonPunktHinterEinerLueckeWirdGezeichnet() {
        // Fuenf dichte Spalten bilden ein Kurvensegment; die letzte liegt weit dahinter (Luecke
        // groesser als das 2,5-fache des mittleren Spaltenabstands) und bleibt ein Einzelsegment.
        val spalten = listOf(0L, 10L, 20L, 30L, 40L, 100_000L).map { spalte(it) }
        val radien = zeichne(spalten = emptyList(), mikrofon = spalten)
        assertEquals(listOf(MIKROFON_PUNKT_RADIUS), radien)
    }

    @Test
    fun mikrofonKurveMitMehrerenSpaltenZeichnetKeinePunkte() {
        val radien = zeichne(spalten = emptyList(), mikrofon = listOf(spalte(0), spalte(10), spalte(20)))
        assertEquals(emptyList<Float>(), radien)
    }
}
