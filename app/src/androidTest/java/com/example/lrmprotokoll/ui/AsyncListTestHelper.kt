package com.example.lrmprotokoll.ui

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.printToLog

private const val LOG_TAG = "AsyncListTestHelper"

/**
 * Room-Flows laufen auf IO; Compose-Idle garantiert noch keine erste Datenemission.
 *
 * CI-Fund (22.09.2026, PR #182): 10s Timeout war unter dem seit Schritt 8 eingefuehrten Test
 * Orchestrator zu knapp - jede Testmethode startet dort in einem frischen Prozess, zahlt also
 * den vollen Room-/Compose-Kaltstart statt ihn sich mit anderen Tests im selben Prozess zu
 * teilen. Zeigte sich als wechselnde ComposeTimeoutException in verschiedenen, voneinander
 * unabhaengigen Home-/Meter-Tests ueber mehrere CI-Laeufe hinweg (nie dieselben Tests zweimal -
 * klassisches Lastflakiness-Muster, kein Logikfehler). Grosszuegiger gefasst statt geraten.
 *
 * CI-Fund (22.09.2026, PR #182, 2. Iteration): die Timeout-Erhoehung allein senkte die
 * Fehlerquote NICHT sichtbar - weiterhin ComposeTimeoutException, nur nach laengerer Wartezeit.
 * Das spricht dagegen, dass es ein reines Zeitproblem ist: die Bedingung tritt in manchen
 * Faellen offenbar gar nicht ein. Statt weiter am Timeout zu drehen, jetzt Diagnose beim
 * endgueltigen Scheitern: ob "home_lazy_column" ueberhaupt existiert UND wie viele
 * text-tragende Knoten insgesamt sichtbar sind, stehen direkt in der Fehlermeldung (im
 * CI-Job-Log sichtbar, kein Artefakt-Download noetig) - unterscheidet "Screen praktisch leer"
 * von "Liste/Screen da, aber anderer Inhalt als erwartet". Der volle Semantics-Baum zusaetzlich
 * in Logcat unter diesem Tag fuer eine tiefere Analyse, falls die Zahlen allein nicht reichen.
 */
internal fun ComposeTestRule.warteUndScrolleZu(matcher: SemanticsMatcher) {
    try {
        waitUntil(timeoutMillis = 20_000) {
            try {
                onNodeWithTag("home_lazy_column").performScrollToNode(matcher)
                true
            } catch (_: AssertionError) {
                false
            }
        }
    } catch (timeout: Throwable) {
        val lazyColumnGefunden = onAllNodesWithTag("home_lazy_column")
            .fetchSemanticsNodes(atLeastOneRootRequired = false).size
        val textKnotenGesamt = onAllNodesWithText("", substring = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false).size
        runCatching { onRoot().printToLog(LOG_TAG) }
        throw AssertionError(
            "warteUndScrolleZu-Timeout - home_lazy_column-Knoten gefunden: $lazyColumnGefunden, " +
                "text-tragende Knoten insgesamt: $textKnotenGesamt " +
                "(home_lazy_column=0: Screen/Liste nicht komponiert; wenige Textknoten insgesamt: " +
                "Screen praktisch leer/haengt fest; viele Textknoten aber Zielknoten fehlt: " +
                "anderer Inhalt sichtbar als erwartet). " +
                "Voller Semantics-Baum in Logcat unter Tag \"$LOG_TAG\".",
            timeout,
        )
    }
}
