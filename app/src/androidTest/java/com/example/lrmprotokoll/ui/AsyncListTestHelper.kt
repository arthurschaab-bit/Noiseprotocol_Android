package com.example.lrmprotokoll.ui

import android.app.Application
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.util.Log
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
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.data.AppDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

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
 *
 * CI-Fund (22.09.2026, PR #182, 5. Iteration): das Logcat des Laufs auf ebf0f63 zeigt im
 * haengenden Testprozess 20s lang KEINE einzige App-Logzeile und systemweit keine Last (kein
 * dexopt, keine uebersprungenen Frames) - der Prozess haengt, er ist nicht bloss langsam. Room 2.8
 * gibt die erste Flow-Emission erst nach syncTriggers() frei, das den einzigen Schreib-Connection
 * in einer IMMEDIATE-Transaktion braucht; Queries und Transaktionen teilen sich ausserdem die vier
 * arch_disk_io-Threads. Um "Zeile fehlt" von "Room blockiert" zu trennen und im zweiten Fall den
 * Blockierer zu sehen, zusaetzlich: ein Thread-Dump im Moment des Timeouts (gefiltert in der
 * Fehlermeldung, vollstaendig im Logcat), eine Room-unabhaengige Zaehlung direkt ueber das
 * Framework-SQLite und dieselbe Abfrage ueber Room mit eigenem Timeout.
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
        // Zuerst, damit die eigenen Diagnose-Abfragen unten den Zustand nicht veraendern.
        val threadDump = threadDumpFuerRoom()
        val dbGroesseBytes = runCatching {
            ApplicationProvider.getApplicationContext<Application>()
                .getDatabasePath("noise_database").length()
        }.getOrDefault(-1)
        val rohZaehlung = zaehleDirektUeberFrameworkSqlite()
        val roomZaehlung = zaehleUeberRoomMitTimeout()
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
                "Direkt per Framework-SQLite: $rohZaehlung. Ueber Room: $roomZaehlung. " +
                "Voller Semantics-Baum und voller Thread-Dump in Logcat unter Tag \"$LOG_TAG\".\n" +
                "Thread-Dump (Room/SQLite/Dispatcher-Threads):\n$threadDump",
            timeout,
        )
    }
}

/**
 * Alle Threads vollstaendig ins Logcat, in die Fehlermeldung nur die fuer Room relevanten: die
 * arch_disk_io-Threads (Query- und Transaction-Executor von Room), die Coroutine-Dispatcher, der
 * Main-Thread sowie jeder Thread, der gerade in Room- oder SQLite-Code steht.
 */
private fun threadDumpFuerRoom(): String = runCatching {
    val alle = Thread.getAllStackTraces()
    alle.forEach { (thread, frames) ->
        Log.e(LOG_TAG, "Thread \"${thread.name}\" ${thread.state}\n" + frames.joinToString("\n") { "    at $it" })
    }
    alle.filter { (thread, frames) ->
        thread.name.startsWith("arch_disk_io") || thread.name.startsWith("DefaultDispatcher") ||
            thread.name == "main" ||
            frames.any { it.className.startsWith("androidx.room") || it.className.startsWith("android.database.sqlite") }
    }.entries.sortedBy { it.key.name }.joinToString("\n") { (thread, frames) ->
        "\"${thread.name}\" ${thread.state}\n" + frames.take(14).joinToString("\n") { "    at $it" }
    }
}.getOrElse { "Thread-Dump fehlgeschlagen: $it" }

/**
 * Liest an Room vorbei ueber eine eigene, nur lesende Framework-Verbindung - funktioniert auch,
 * wenn Rooms Executor oder Schreib-Connection blockiert ist, und trennt so "Zeile fehlt" von
 * "Zeile da, aber Room liefert nicht".
 */
private fun zaehleDirektUeberFrameworkSqlite(): String = runCatching {
    val pfad = ApplicationProvider.getApplicationContext<Application>().getDatabasePath("noise_database").path
    SQLiteDatabase.openDatabase(pfad, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        val journal = db.rawQuery("PRAGMA journal_mode", null).use { it.moveToFirst(); it.getString(0) }
        val aktiv = DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM noise_records WHERE deletedAt IS NULL", null)
        val muster = DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM reference_sounds", null)
        "journal_mode=$journal, aktive noise_records=$aktiv, reference_sounds=$muster"
    }
}.getOrElse { "Fehler ${it.javaClass.simpleName}: ${it.message}" }

/** Dieselbe Abfrage wie die Home-Liste, aber ueber Room und mit eigenem 3s-Timeout. */
private fun zaehleUeberRoomMitTimeout(): String = runCatching {
    val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
    val db = app.container.database
    val zustand = "Instanz=AppDatabase-Singleton: ${db === AppDatabase.getDatabase(app)}, isOpen=${db.isOpen}"
    val anzahl = runBlocking { withTimeoutOrNull(3_000) { db.noiseDao().getAlleAktiven().size } }
    "$zustand, aktive noise_records=${anzahl ?: "TIMEOUT nach 3000ms"}"
}.getOrElse { "Fehler ${it.javaClass.simpleName}: ${it.message}" }
