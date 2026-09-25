package com.example.lrmprotokoll.ui

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode

/**
 * CI-Fund (23.09.2026, PR #187): Inhalte, die ein Screen per Room-Flow nachlaedt, sind nach
 * `waitForIdle()` unter Robolectric nicht zwingend da - `waitForIdle()` wartet nicht auf Rooms
 * IO-Executor. Ein `performScrollToNode` direkt danach wirft dann "No node found ... in
 * scrollable container", sobald der Runner ausgelastet ist (so in `DiagnoseScreenComposeTest`).
 * Nachgestellt mit kuenstlich um 1,5 s verzoegertem Room-IO: die alte Reihenfolge (erst scrollen,
 * dann warten) scheitert damit jedes Mal genau so, diese Hilfe besteht.
 *
 * Deshalb wird das Scrollen selbst wiederholt, bis der Knoten gefunden ist.
 */
internal fun ComposeTestRule.scrolleZuSobaldGeladen(
    listenTag: String,
    matcher: SemanticsMatcher,
    timeoutMillis: Long = 30_000,
) {
    waitUntil(timeoutMillis) {
        runCatching { onNodeWithTag(listenTag).performScrollToNode(matcher) }.isSuccess
    }
}
