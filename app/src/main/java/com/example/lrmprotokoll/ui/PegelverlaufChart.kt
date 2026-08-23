package com.example.lrmprotokoll.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.lrmprotokoll.data.NoiseRecord
import com.example.lrmprotokoll.messreihe.Ausfallband
import com.example.lrmprotokoll.messreihe.ChartSpalte
import com.example.lrmprotokoll.ui.theme.statusColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * Interaktiver Pegelverlauf-Chart für Tages- und Langzeitmessungen (UX-Briefing Punkte 6, 7, 8, 12, 17, 18).
 *
 * Unterstützt:
 * - Horizontales Pinch-to-Zoom (1x bis 10x) und Verschieben (Pan) entlang der 24h-Zeitachse
 * - Markierte Ausfallbänder (schraffierte Verbindungsausfälle)
 * - Ereignis-Pins (vom Nutzer markierte oder von KI erkannte Vorfälle)
 * - Gestrichelte Referenzlinien für Schwellenwert (rot) und LAeq (grün)
 * - Revisionssichere Achsenskalierung & OLED-optimierte Farbkontraste
 */
@Composable
fun PegelverlaufChart(
    spalten: List<ChartSpalte>,
    ausfallbaender: List<Ausfallband>,
    sessionStart: Long,
    sessionEnde: Long,
    modifier: Modifier = Modifier,
    events: List<NoiseRecord> = emptyList(),
    thresholdDb: Double? = null,
    laeqDb: Double? = null,
    height: Dp = 180.dp,
    isLive: Boolean = false,
    enableZoom: Boolean = true,
) {
    if (spalten.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(height)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = androidx.compose.ui.res.stringResource(com.example.lrmprotokoll.R.string.protocol_detail_no_chart_data),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    var scaleX by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var selectedEvent by remember { mutableStateOf<NoiseRecord?>(null) }

    val textMeasurer = rememberTextMeasurer()
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val detailTimeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    val statusColors = MaterialTheme.colorScheme.statusColors

    val curveColor = MaterialTheme.colorScheme.primary
    val areaColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    val thresholdColor = statusColors.error
    val leqColor = statusColors.connected
    val outageColor = statusColors.outageBand

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(enableZoom) {
                    if (enableZoom) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scaleX = (scaleX * zoom).coerceIn(1f, 10f)
                            val maxOffset = (scaleX - 1f)
                            if (scaleX > 1f) {
                                val deltaFraction = pan.x / size.width
                                offsetX = (offsetX - deltaFraction / scaleX).coerceIn(0f, 1f - (1f / scaleX))
                            } else {
                                offsetX = 0f
                            }
                        }
                    }
                }
        ) {
            val yAxisWidth = 38.dp.toPx()
            val xAxisHeight = 22.dp.toPx()
            val plotWidth = (size.width - yAxisWidth).coerceAtLeast(1f)
            val plotHeight = (size.height - xAxisHeight).coerceAtLeast(1f)

            val rawMinDb = spalten.minOf { it.minDb }
            val rawMaxDb = spalten.maxOf { it.maxDb }

            val minScaleDb = floor((rawMinDb - 2.0) / 10.0) * 10.0
            val maxScaleDb = ceil((maxOf(rawMaxDb, thresholdDb ?: 0.0) + 4.0) / 10.0) * 10.0
            val dbSpanne = (maxScaleDb - minScaleDb).coerceAtLeast(10.0)

            val gesamtSekunden = ((sessionEnde - sessionStart) / 1000).coerceAtLeast(1)

            // Zoom-Transformation: Berechne sichtbares Zeitfenster
            val sichtbarerAnteil = 1f / scaleX
            val startRatio = offsetX.coerceIn(0f, 1f - sichtbarerAnteil)
            val endRatio = startRatio + sichtbarerAnteil

            fun x(sekunden: Long): Float {
                val ratio = (sekunden.toFloat() / gesamtSekunden)
                val transformedRatio = (ratio - startRatio) / sichtbarerAnteil
                return yAxisWidth + transformedRatio * plotWidth
            }

            fun y(db: Double): Float {
                val ratio = ((db - minScaleDb) / dbSpanne).coerceIn(0.0, 1.0).toFloat()
                return plotHeight - (ratio * plotHeight)
            }

            val textStyle = TextStyle(fontSize = 10.sp, color = labelColor)

            // 1. Y-Achsen-Gitterlinien und -Beschriftung
            val ySteps = if (dbSpanne >= 30) 4 else 3
            val dbStep = dbSpanne / ySteps
            for (i in 0..ySteps) {
                val dbVal = minScaleDb + i * dbStep
                val yPos = y(dbVal)

                drawLine(
                    color = gridColor,
                    start = Offset(yAxisWidth, yPos),
                    end = Offset(size.width, yPos),
                    strokeWidth = 1.dp.toPx()
                )

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

            // 2. Schwellenwert & Leq Referenzlinien (gestrichelt)
            thresholdDb?.let { thresh ->
                val threshY = y(thresh)
                if (threshY in 0f..plotHeight) {
                    val dashPathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                    drawLine(
                        color = thresholdColor.copy(alpha = 0.85f),
                        start = Offset(yAxisWidth, threshY),
                        end = Offset(size.width, threshY),
                        strokeWidth = 1.5f.dp.toPx(),
                        pathEffect = dashPathEffect
                    )
                }
            }

            laeqDb?.let { leq ->
                val leqY = y(leq)
                if (leqY in 0f..plotHeight) {
                    val dashPathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                    drawLine(
                        color = leqColor.copy(alpha = 0.7f),
                        start = Offset(yAxisWidth, leqY),
                        end = Offset(size.width, leqY),
                        strokeWidth = 1.5f.dp.toPx(),
                        pathEffect = dashPathEffect
                    )
                }
            }

            // 3. Ausfallbänder (schraffierte Verbindungsausfälle)
            ausfallbaender.forEach { band ->
                val vonSek = ((band.von - sessionStart) / 1000).coerceIn(0, gesamtSekunden)
                val bisSek = (((band.bis ?: sessionEnde) - sessionStart) / 1000).coerceIn(0, gesamtSekunden)
                val xStart = x(vonSek).coerceIn(yAxisWidth, size.width)
                val xEnd = x(bisSek).coerceIn(yAxisWidth, size.width)
                val rectWidth = (xEnd - xStart)

                if (rectWidth > 0f) {
                    drawRect(
                        color = outageColor,
                        topLeft = Offset(xStart, 0f),
                        size = Size(rectWidth, plotHeight)
                    )
                }
            }

            // 4. Pegeldaten in Segmente aufteilen & Kurven zeichnen
            val segmente = mutableListOf<MutableList<ChartSpalte>>()
            var aktuellesSegment = mutableListOf<ChartSpalte>()

            spalten.forEach { spalte ->
                if (aktuellesSegment.isEmpty()) {
                    aktuellesSegment.add(spalte)
                } else {
                    val vorherige = aktuellesSegment.last()
                    val deltaSek = spalte.zeitOffsetSekunden - vorherige.zeitOffsetSekunden
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

            segmente.forEach { segment ->
                if (segment.isEmpty()) return@forEach

                // Min/Max Area
                val bandPfad = Path().apply {
                    var first = true
                    segment.forEach { spalte ->
                        val px = x(spalte.zeitOffsetSekunden)
                        val pyMax = y(spalte.maxDb)
                        if (first) {
                            moveTo(px, pyMax)
                            first = false
                        } else {
                            lineTo(px, pyMax)
                        }
                    }
                    for (index in segment.indices.reversed()) {
                        val spalte = segment[index]
                        lineTo(x(spalte.zeitOffsetSekunden), y(spalte.minDb))
                    }
                    close()
                }
                drawPath(bandPfad, color = areaColor)

                // Mittelwertlinie
                val mittelPfad = Path().apply {
                    segment.forEachIndexed { index, spalte ->
                        val px = x(spalte.zeitOffsetSekunden)
                        val py = y(spalte.mittelDb)
                        if (index == 0) moveTo(px, py) else lineTo(px, py)
                    }
                }
                drawPath(mittelPfad, color = curveColor, style = Stroke(width = 2.dp.toPx()))
            }

            // 5. Ereignis-Pins auf der Zeitachse
            events.forEach { event ->
                val eventSec = ((event.timestamp - sessionStart) / 1000).coerceIn(0, gesamtSekunden)
                val eventX = x(eventSec)
                if (eventX in yAxisWidth..size.width) {
                    val eventDb = event.calibratedDbA ?: event.dbValue
                    val eventY = y(eventDb)

                    drawCircle(
                        color = Color(0xFFFFA000),
                        radius = 4.5f.dp.toPx(),
                        center = Offset(eventX, eventY)
                    )
                    drawCircle(
                        color = Color(0xFFFFD54F),
                        radius = 2.5f.dp.toPx(),
                        center = Offset(eventX, eventY)
                    )
                }
            }

            // 6. X-Achsen-Gitter & Zeitbeschriftung (angepasst an Zoomfenster)
            val xTicks = 4
            val sichtbareSekunden = (gesamtSekunden * sichtbarerAnteil).toLong()
            val startSek = (gesamtSekunden * startRatio).toLong()
            val timeStepSec = (sichtbareSekunden / xTicks).coerceAtLeast(1)

            for (i in 0..xTicks) {
                val tickSec = startSek + i * timeStepSec
                val xPos = x(tickSec)
                if (xPos in yAxisWidth..size.width) {
                    val tickTimeMillis = sessionStart + (tickSec * 1000)

                    drawLine(
                        color = gridColor,
                        start = Offset(xPos, plotHeight),
                        end = Offset(xPos, plotHeight + 4.dp.toPx()),
                        strokeWidth = 1.dp.toPx()
                    )

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
            }

            // 7. Live-Puls-Punkt am aktuellen Datenpunkt
            if (isLive && spalten.isNotEmpty()) {
                val letzterPunkt = spalten.last()
                val px = x(letzterPunkt.zeitOffsetSekunden)
                if (px in yAxisWidth..size.width) {
                    val py = y(letzterPunkt.mittelDb)
                    drawCircle(
                        color = curveColor,
                        radius = 4.5f.dp.toPx(),
                        center = Offset(px, py)
                    )
                    drawCircle(
                        color = statusColors.connected,
                        radius = 2.5f.dp.toPx(),
                        center = Offset(px, py)
                    )
                }
            }
        }

        // Zoom Reset Button (wird eingeblendet wenn Zoom > 1x)
        if (scaleX > 1.05f) {
            Surface(
                onClick = {
                    scaleX = 1f
                    offsetX = 0f
                },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                border = ButtonDefaults.outlinedButtonBorder,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = androidx.compose.ui.res.stringResource(com.example.lrmprotokoll.R.string.action_reset_zoom),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "%.1fx Reset".format(Locale.US, scaleX),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
