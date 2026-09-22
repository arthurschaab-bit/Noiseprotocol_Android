package com.example.lrmprotokoll.ui

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode

/**
 * Room-Flows laufen auf IO; Compose-Idle garantiert noch keine erste Datenemission.
 *
 * CI-Fund (22.09.2026, PR #182): 10s Timeout war unter dem seit Schritt 8 eingefuehrten Test
 * Orchestrator zu knapp - jede Testmethode startet dort in einem frischen Prozess, zahlt also
 * den vollen Room-/Compose-Kaltstart statt ihn sich mit anderen Tests im selben Prozess zu
 * teilen. Zeigte sich als wechselnde ComposeTimeoutException in verschiedenen, voneinander
 * unabhaengigen Home-/Meter-Tests ueber mehrere CI-Laeufe hinweg (nie dieselben Tests zweimal -
 * klassisches Lastflakiness-Muster, kein Logikfehler). Grosszuegiger gefasst statt geraten.
 */
internal fun ComposeTestRule.warteUndScrolleZu(matcher: SemanticsMatcher) {
    waitUntil(timeoutMillis = 20_000) {
        try {
            onNodeWithTag("home_lazy_column").performScrollToNode(matcher)
            true
        } catch (_: AssertionError) {
            false
        }
    }
}
