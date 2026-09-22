package com.example.lrmprotokoll.ui

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.printToLog
import androidx.test.core.app.ApplicationProvider

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
 * Faellen offenbar gar nicht ein.
 *
 * CI-Fund (22.09.2026, PR #182, 3. Iteration): die Textknoten-ANZAHL allein (stabil 16-17,
 * ueber verschiedene Tests/Zielmatcher hinweg) reichte nicht, um "leere Liste" von "Liste mit
 * unerwartetem Inhalt" zu unterscheiden. Jetzt die tatsaechlichen Textwerte (bis zu 30) direkt
 * in der Fehlermeldung - im CI-Job-Log sichtbar, kein Artefakt-Download noetig. Der volle
 * Semantics-Baum zusaetzlich in Logcat unter diesem Tag fuer eine noch tiefere Analyse.
 *
 * CI-Fund (22.09.2026, PR #182, 4. Iteration): die Textwerte zeigten den echten Leerzustand
 * (R.string.empty_records_title/-desc) statt der erwarteten Aufnahme - der Screen ist also
 * korrekt komponiert, aber dao.getAll().collectAsState(initial = emptyList()) (MainActivity.kt)
 * hat die per @Before synchron eingefuegte Zeile nicht rechtzeitig gesehen. Bevor daran etwas
 * geaendert wird: zwei zusaetzliche, gezielt messbare Groessen in der Fehlermeldung, um zwischen
 * "generische Race" und "Datenbankdatei waechst ueber den ~218-Test-Orchestrator-Lauf, weil
 * kein clearPackageData zwischen Testmethoden laeuft (bewusste Repo-Konvention) und nicht jeder
 * Test aufraeumt" zu unterscheiden: die tatsaechlich verstrichene Wartezeit UND die aktuelle
 * Groesse der "noise_database"-Datei auf der Platte.
 */
internal fun ComposeTestRule.warteUndScrolleZu(matcher: SemanticsMatcher) {
    val start = System.currentTimeMillis()
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
        val elapsedMs = System.currentTimeMillis() - start
        val dbGroesseBytes = runCatching {
            ApplicationProvider.getApplicationContext<Application>()
                .getDatabasePath("noise_database").length()
        }.getOrDefault(-1)
        val lazyColumnGefunden = onAllNodesWithTag("home_lazy_column")
            .fetchSemanticsNodes(atLeastOneRootRequired = false).size
        val textWerte = onAllNodesWithText("", substring = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
            .map { it.text }
        runCatching { onRoot().printToLog(LOG_TAG) }
        throw AssertionError(
            "warteUndScrolleZu-Timeout nach ${elapsedMs}ms, noise_database-Dateigroesse: " +
                "$dbGroesseBytes Bytes - home_lazy_column-Knoten gefunden: $lazyColumnGefunden, " +
                "${textWerte.size} Textwerte sichtbar: ${textWerte.take(30)}. " +
                "Voller Semantics-Baum in Logcat unter Tag \"$LOG_TAG\".",
            timeout,
        )
    }
}
