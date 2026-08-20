package com.example.lrmprotokoll.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.lrmprotokoll.messreihe.Ausfallband
import com.example.lrmprotokoll.messreihe.ChartSpalte
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * Zeichnet den Pegelverlauf einer Session mit Achsenbeschriftung (dB auf der Y-Achse,
 * Uhrzeit auf der X-Achse), Min/Max-Band, Mittelwert-Linie (LAeq) und markierten Ausfallbändern.
 *
 * Lücken / Verbindungsausfälle werden nicht mehr irreführend diagonal überbrückt, sondern als
 * Pfadunterbrechungen gerendert.
 */
@Composable
fun PegelverlaufChart(
    spalten: List<ChartSpalte>,
    ausfallbaender: List<Ausfallband>,
    sessionStart: Long,
    sessionEnde: Long,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    isLive: Boolean = false,
) {
    if (spalten.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth().height(height).padding(16.dp)) {
            Text("Keine Messwerte für den Pegelverlauf.", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    val textMeasurer = rememberTextMeasurer()
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val yAxisWidth = 40.dp.toPx()
        val xAxisHeight = 22.dp.toPx()
        val plotWidth = (size.width - yAxisWidth).coerceAtLeast(1f)
        val plotHeight = (size.height - xAxisHeight).coerceAtLeast(1f)

        val rawMinDb = spalten.minOf { it.minDb }
        val rawMaxDb = spalten.maxOf { it.maxDb }

        // Schöne gerundete Y-Achsenskalierung mit 5 dB Puffer
        val minScaleDb = floor((rawMinDb - 2.0) / 10.0) * 10.0
        val maxScaleDb = ceil((rawMaxDb + 2.0) / 10.0) * 10.0
        val dbSpanne = (maxScaleDb - minScaleDb).coerceAtLeast(10.0)

        val gesamtSekunden = ((sessionEnde - sessionStart) / 1000).coerceAtLeast(1)

        fun x(sekunden: Long): Float {
            val ratio = (sekunden.toFloat() / gesamtSekunden).coerceIn(0f, 1f)
            return yAxisWidth + ratio * plotWidth
        }

        fun y(db: Double): Float {
            val ratio = ((db - minScaleDb) / dbSpanne).coerceIn(0.0, 1.0).toFloat()
            return plotHeight - (ratio * plotHeight)
        }

        val textStyle = TextStyle(fontSize = 10.sp, color = labelColor)

        // 1. Y-Achsen-Gitterlinien und -Beschriftung (3 bis 5 Ticks)
        val ySteps = if (dbSpanne >= 30) 4 else 3
        val dbStep = dbSpanne / ySteps
        for (i in 0..ySteps) {
            val dbVal = minScaleDb + i * dbStep
            val yPos = y(dbVal)

            // Horizontale Gitterlinie
            drawLine(
                color = gridColor,
                start = Offset(yAxisWidth, yPos),
                end = Offset(size.width, yPos),
                strokeWidth = 1.dp.toPx()
            )

            // Y-Label
            val labelText = "${dbVal.toInt()}"
            val measuredText = textMeasurer.measure(labelText, textStyle)
            drawText(
                textMeasurer = textMeasurer,
                text = labelText,
                style = textStyle,
                topLeft = Offset(
                    x = (yAxisWidth - measuredText.size.width - 4.dp.toPx()).coerceAtLeast(0f),
                    y = yPos - (measuredText.size.height / 2f)
                )
            )
        }

        // 2. Ausfallbänder (rote Markierungsbereiche im Hintergrund)
        ausfallbaender.forEach { band ->
            val vonSekunden = ((band.von - sessionStart) / 1000).coerceIn(0, gesamtSekunden)
            val bisSekunden = (((band.bis ?: sessionEnde) - sessionStart) / 1000).coerceIn(0, gesamtSekunden)
            val xStart = x(vonSekunden)
            val xEnd = x(bisSekunden)
            val rectWidth = (xEnd - xStart).coerceAtLeast(2.dp.toPx())

            drawRect(
                color = Color.Red.copy(alpha = 0.18f),
                topLeft = Offset(xStart, 0f),
                size = Size(rectWidth, plotHeight),
            )
        }

        // 3. Pegeldaten in Segmente aufteilen (Lücken abfangen)
        // Wenn der Zeitabstand zwischen zwei Datenpunkten größer als 2x durchschnittlicher Abstand
        // oder > 45s ist, teilen wir die Segmente, um diagonale Verbindungslinien über Lücken zu vermeiden.
        val segmente = mutableListOf<MutableList<ChartSpalte>>()
        var aktuellesSegment = mutableListOf<ChartSpalte>()

        spalten.forEach { spalte ->
            if (aktuellesSegment.isEmpty()) {
                aktuellesSegment.add(spalte)
            } else {
                val vorherige = aktuellesSegment.last()
                val deltaSek = spalte.zeitOffsetSekunden - vorherige.zeitOffsetSekunden
                // Lückenerkennung: Zeitabstand größer als 60s
                if (deltaSek > 60L) {
                    segmente.add(aktuellesSegment)
                    aktuellesSegment = mutableListOf(spalte)
                } else {
                    aktuellesSegment.add(spalte)
                }
            }
        }
        if (aktuellesSegment.isNotEmpty()) {
            segmente.add(aktuellesSegment)
        }

        // 4. Min/Max-Band & LAeq-Mittelwertkurve je Segment zeichnen
        segmente.forEach { segment ->
            if (segment.isEmpty()) return@forEach

            // Min/Max Band
            val bandPfad = Path().apply {
                segment.forEachIndexed { index, spalte ->
                    val px = x(spalte.zeitOffsetSekunden)
                    val pyMax = y(spalte.maxDb)
                    if (index == 0) moveTo(px, pyMax) else lineTo(px, pyMax)
                }
                for (index in segment.indices.reversed()) {
                    val spalte = segment[index]
                    lineTo(x(spalte.zeitOffsetSekunden), y(spalte.minDb))
                }
                close()
            }
            drawPath(bandPfad, color = Color(0xFF1976D2).copy(alpha = 0.22f))

            // Mittelwertlinie
            val mittelPfad = Path().apply {
                segment.forEachIndexed { index, spalte ->
                    val px = x(spalte.zeitOffsetSekunden)
                    val py = y(spalte.mittelDb)
                    if (index == 0) moveTo(px, py) else lineTo(px, py)
                }
            }
            drawPath(mittelPfad, color = Color(0xFF0D47A1), style = Stroke(width = 2.5f.dp.toPx()))
        }

        // 5. X-Achsen-Gitter & Zeitbeschriftung
        val xTicks = 4
        val timeStepSec = gesamtSekunden / xTicks
        for (i in 0..xTicks) {
            val tickSec = i * timeStepSec
            val xPos = x(tickSec)
            val tickTimeMillis = sessionStart + (tickSec * 1000)

            // Kurzer Tick-Strich
            drawLine(
                color = gridColor,
                start = Offset(xPos, plotHeight),
                end = Offset(xPos, plotHeight + 4.dp.toPx()),
                strokeWidth = 1.dp.toPx()
            )

            // Zeitstempel
            val timeText = timeFormat.format(Date(tickTimeMillis))
            val measured = textMeasurer.measure(timeText, textStyle)
            val textX = (xPos - (measured.size.width / 2f)).coerceIn(
                yAxisWidth,
                size.width - measured.size.width
            )
            drawText(
                textMeasurer = textMeasurer,
                text = timeText,
                style = textStyle,
                topLeft = Offset(textX, plotHeight + 6.dp.toPx())
            )
        }

        // 6. Live-Puls-Punkt am aktuellen Datenpunkt
        if (isLive && spalten.isNotEmpty()) {
            val letzterPunkt = spalten.last()
            val px = x(letzterPunkt.zeitOffsetSekunden)
            val py = y(letzterPunkt.mittelDb)

            drawCircle(
                color = Color(0xFF0D47A1),
                radius = 4.dp.toPx(),
                center = Offset(px, py)
            )
            drawCircle(
                color = Color(0xFF4CAF50),
                radius = 2.5f.dp.toPx(),
                center = Offset(px, py)
            )
        }
    }
}
