package com.example.lrmprotokoll.ui

import android.app.Application
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.compose.runtime.snapshots.Snapshot
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
import com.example.lrmprotokoll.data.NoiseRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

private const val LOG_TAG = "AsyncListTestHelper"

/**
 * Room-Flows laufen auf IO; Compose-Idle garantiert noch keine erste Datenemission. Deshalb auf
 * den Zielknoten warten statt nur auf waitForIdle().
 *
 * CI-Fund (22.09.2026, PR #182, aufgeklaert): unter dem Test Orchestrator blieb die Home-Liste
 * sporadisch leer. Weder Room noch Zeitmangel waren die Ursache (die Timeout-Erhoehung auf 20s
 * hatte nichts bewirkt und ist zurueckgenommen). NoiseProtocolApp las records/references nur im
 * LazyColumn-Builder; beobachtet wurden sie damit allein ueber den abgeleiteten Zustand der
 * LazyColumn aus der ersten Messphase. Kam die erste Emission 2-3 ms nach dessen erster
 * Auswertung an, wurde der Inhalt nie neu ausgewertet - per Diagnose-Logging im Emulator belegt,
 * Fix in NoiseProtocolApp (Lesen im Kompositions-Scope). Die Diagnose unten bleibt fuer
 * kuenftige Faelle: sie meldet beim Timeout Thread-Dump, Datenbankinhalt direkt und ueber Room,
 * Rooms Beobachterzaehler und das Ergebnis zweier Eingriffe (sendApplyNotifications, zusaetzlicher
 * Insert), die "Emission kommt nicht an" von "Aenderung wird nicht neu gezeichnet" trennen.
 */
internal fun ComposeTestRule.warteUndScrolleZu(matcher: SemanticsMatcher) {
    val start = System.currentTimeMillis()
    try {
        waitUntil(timeoutMillis = 10_000) {
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
        // Vor jedem frischen Flow unten, der die Zaehler sonst selbst erhoehen wuerde.
        val beobachter = roomBeobachterZustand()
        val rohZaehlung = zaehleDirektUeberFrameworkSqlite()
        val roomZaehlung = zaehleUeberRoomMitTimeout()
        val lazyColumnGefunden = onAllNodesWithTag("home_lazy_column")
            .fetchSemanticsNodes(atLeastOneRootRequired = false).size
        val textWerte = onAllNodesWithText("", substring = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
            .map { it.text }
        runCatching { onRoot().printToLog(LOG_TAG) }
        // Eingriffe erst NACH allen reinen Beobachtungen oben - sie veraendern den Zustand.
        val nachApply = pruefeNachEingriff(matcher) { runOnUiThread { Snapshot.sendApplyNotifications() } }
        val nachInsert = pruefeNachEingriff(matcher) {
            runBlocking {
                ApplicationProvider.getApplicationContext<LaermprotokollApp>().container.database.noiseDao()
                    .insert(NoiseRecord(timestamp = System.currentTimeMillis(), amplitude = 1.0, filePath = "", label = "DiagnoseSonde"))
            }
        }
        throw AssertionError(
            "warteUndScrolleZu-Timeout nach ${elapsedMs}ms, noise_database-Dateigroesse: " +
                "$dbGroesseBytes Bytes - home_lazy_column-Knoten gefunden: $lazyColumnGefunden, " +
                "${textWerte.size} Textwerte sichtbar: ${textWerte.take(30)}. " +
                "Direkt per Framework-SQLite: $rohZaehlung. Ueber Room: $roomZaehlung. " +
                "Room-Beobachter: $beobachter. " +
                "Nach Snapshot.sendApplyNotifications(): $nachApply. Nach zusaetzlichem Insert: $nachInsert. " +
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

/**
 * Dieselben Abfragen wie die Home-Liste ueber Room, jeweils mit eigenem 3s-Timeout: einmal direkt
 * (suspend), einmal als FRISCHER Flow (getAll()/getAllReferences() + first()) ausserhalb von
 * Compose - letzteres durchlaeuft wie der UI-Collector syncTriggers() und die Initial-Emission.
 */
private fun zaehleUeberRoomMitTimeout(): String = runCatching {
    val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
    val db = app.container.database
    val dao = db.noiseDao()
    val zustand = "Instanz=AppDatabase-Singleton: ${db === AppDatabase.getDatabase(app)}, isOpen=${db.isOpen}"
    val direkt = runBlocking { withTimeoutOrNull(3_000) { dao.getAlleAktiven().size } }
    val muster = runCatching { dao.getAllReferencesBlocking().size.toString() }.getOrElse { "Fehler: $it" }
    val start = System.currentTimeMillis()
    val flowRecords = runBlocking { withTimeoutOrNull(3_000) { dao.getAll().first().size } }
    val flowMuster = runBlocking { withTimeoutOrNull(3_000) { dao.getAllReferences().first().size } }
    val flowMs = System.currentTimeMillis() - start
    "$zustand, direkt: aktive noise_records=${direkt ?: "TIMEOUT"}, reference_sounds=$muster; " +
        "frischer Flow: getAll()=${flowRecords ?: "TIMEOUT"}, getAllReferences()=${flowMuster ?: "TIMEOUT"} " +
        "(beide zusammen ${flowMs}ms)"
}.getOrElse { "Fehler ${it.javaClass.simpleName}: ${it.message}" }

/**
 * Rooms interner Zustand der Tabellenbeobachtung per Reflection (nur Testcode, Room 2.8.4:
 * InvalidationTracker.implementation -> TriggerBasedInvalidationTracker.observedTableStates).
 * Fuenf Proben ueber eine Sekunde, damit "Collector weg" (0), "wartet" (stabil) und "wird
 * staendig neu gestartet" (schwankend) unterscheidbar sind.
 */
private fun roomBeobachterZustand(): String = runCatching {
    val db = ApplicationProvider.getApplicationContext<LaermprotokollApp>().container.database
    val tracker = feld(db.invalidationTracker, "implementation")
    @Suppress("UNCHECKED_CAST")
    val tabellenIds = feld(tracker, "tableIdLookup") as Map<String, Int>
    val zustaende = feld(tracker, "observedTableStates")
    val zaehler = feld(zustaende, "tableObserversCount") as LongArray
    val beobachtet = listOf("noise_records", "reference_sounds", "sessions")
    val proben = (1..5).map { probe ->
        if (probe > 1) Thread.sleep(250)
        beobachtet.joinToString(prefix = "{", postfix = "}") { name ->
            "$name=${tabellenIds[name]?.let { zaehler[it] } ?: "?"}"
        }
    }
    "Zaehler $proben, needsSync=${feld(zustaende, "needsSync")}, " +
        "inProgressSync=${feld(zustaende, "inProgressSync")}, onSyncLock=${feld(zustaende, "onSyncLock")}, " +
        "pendingRefresh=${feld(tracker, "pendingRefresh")}"
}.getOrElse { "Reflection fehlgeschlagen: $it" }

/**
 * Fuehrt [eingriff] aus und prueft danach bis zu 3s, ob der Zielknoten erreichbar wird - und, als
 * zweites Signal unabhaengig vom Ziel, wie viele Textknoten danach sichtbar sind (bei befuellter
 * Liste mehr als die 19 des Leerzustands).
 */
private fun ComposeTestRule.pruefeNachEingriff(matcher: SemanticsMatcher, eingriff: () -> Unit): String = runCatching {
    eingriff()
    val erreichbar = runCatching {
        waitUntil(timeoutMillis = 3_000) {
            try {
                onNodeWithTag("home_lazy_column").performScrollToNode(matcher)
                true
            } catch (_: AssertionError) {
                false
            }
        }
    }.isSuccess
    val texte = onAllNodesWithText("", substring = true).fetchSemanticsNodes(atLeastOneRootRequired = false).size
    "Ziel erreichbar=$erreichbar, Textknoten=$texte"
}.getOrElse { "Fehler ${it.javaClass.simpleName}: ${it.message}" }

private fun feld(objekt: Any?, name: String): Any? {
    checkNotNull(objekt) { "Kein Objekt fuer Feld $name" }
    var klasse: Class<*>? = objekt.javaClass
    while (klasse != null) {
        val treffer = klasse.declaredFields.firstOrNull { it.name == name }
        if (treffer != null) return treffer.apply { isAccessible = true }.get(objekt)
        klasse = klasse.superclass
    }
    error("Feld $name nicht in ${objekt.javaClass.name}")
}
