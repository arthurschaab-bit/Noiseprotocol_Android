package com.example.lrmprotokoll.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Rechnet eine Position auf der Schnellscroll-Leiste deterministisch auf einen Listenindex ab.
 * Als reine Funktion separat testbar; dadurch haengt die 20k+-Listen-Navigation nicht von
 * Pixelgroessen eines bestimmten Testgeraets ab.
 */
internal fun fastScrollTargetIndex(fraction: Float, itemCount: Int): Int {
    if (itemCount <= 1) return 0
    return (fraction.coerceIn(0f, 1f) * (itemCount - 1)).roundToInt()
        .coerceIn(0, itemCount - 1)
}

/**
 * Schlanke Fast-Scroll-Leiste fuer sehr grosse [androidx.compose.foundation.lazy.LazyColumn]s.
 *
 * Nur ein schmaler Bereich am rechten Rand faengt Zeigerereignisse ab; normales Scrollen in der
 * Liste bleibt unveraendert. Beim Antippen oder Ziehen wird direkt auf den aus der relativen
 * Position berechneten Lazy-List-Index gesprungen. Damit bleiben auch 20.000+ Ereignisse
 * bedienbar, ohne alle Zeilen gleichzeitig zu materialisieren.
 */
fun Modifier.fastScrollBar(
    listState: LazyListState,
    itemCount: Int,
    minimumItemCount: Int = 200,
): Modifier = composed {
    if (itemCount < minimumItemCount) return@composed this

    val density = LocalDensity.current
    val touchWidthPx = with(density) { 32.dp.toPx() }
    val trackWidthPx = with(density) { 3.dp.toPx() }
    val thumbWidthPx = with(density) { 7.dp.toPx() }
    val minThumbHeightPx = with(density) { 40.dp.toPx() }
    val trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.24f)
    val thumbColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)

    this
        .drawWithContent {
            drawContent()
            val layout = listState.layoutInfo
            val visibleCount = layout.visibleItemsInfo.size.coerceAtLeast(1)
            if (itemCount <= visibleCount || size.height <= 0f) return@drawWithContent

            val thumbFraction = (visibleCount.toFloat() / itemCount.toFloat()).coerceIn(0f, 1f)
            val thumbHeight = max(minThumbHeightPx, size.height * thumbFraction).coerceAtMost(size.height)
            val scrollRangePx = (size.height - thumbHeight).coerceAtLeast(0f)
            val maxFirstIndex = (itemCount - visibleCount).coerceAtLeast(1)
            val firstIndex = listState.firstVisibleItemIndex.coerceIn(0, maxFirstIndex)
            val thumbTop = (firstIndex.toFloat() / maxFirstIndex.toFloat()) * scrollRangePx

            val trackX = size.width - trackWidthPx
            drawRect(
                color = trackColor,
                topLeft = Offset(trackX, 0f),
                size = Size(trackWidthPx, size.height),
            )
            drawRoundRect(
                color = thumbColor,
                topLeft = Offset(size.width - thumbWidthPx, thumbTop),
                size = Size(thumbWidthPx, thumbHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(thumbWidthPx / 2f),
            )
        }
        .pointerInput(itemCount) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // Ausserhalb des schmalen rechten Bedienbereichs nichts konsumieren: dort soll
                // LazyColumn ihre normale Touch-/Fling-Logik behalten.
                if (down.position.x < size.width - touchWidthPx) return@awaitEachGesture

                fun fractionFor(y: Float): Float = if (size.height <= 0) 0f
                else (y / size.height.toFloat()).coerceIn(0f, 1f)

                down.consume()
                listState.scrollToItem(fastScrollTargetIndex(fractionFor(down.position.y), itemCount))

                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    change.consume()
                    listState.scrollToItem(fastScrollTargetIndex(fractionFor(change.position.y), itemCount))
                }
            }
        }
}
