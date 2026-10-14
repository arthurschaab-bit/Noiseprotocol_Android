package com.example.lrmprotokoll.testhilfen

import androidx.compose.runtime.snapshots.Snapshot
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Haelt fest, WER wann in globalen Compose-State schreibt - auch zwischen zwei Tests.
 *
 * **Warum.** Untersuchung AppNotIdle, PR #280, Hypothese C-H10. Gemessen ist: im Haenger ist
 * `GlobalSnapshotManager.sent` wahr, `AndroidUiDispatcher.Main` haelt eine wartende Fortsetzung und
 * glaubt, Handler-Nachricht und Choreographer-Callback seien unterwegs, in der Looper-Warteschlange
 * steht aber nichts. Im Bytecode von Robolectric 4.16.1 gelesen: `AndroidTestEnvironment` ruft zu
 * Beginn jedes Tests `ShadowPausedLooper.resetLoopers()` (verwirft alle Nachrichten und setzt die
 * Choreographer zurueck), der statische Zustand des Dispatchers bleibt je Sandbox bestehen.
 * Vermutet, NICHT gemessen: ein Schreibzugriff aus einem Fremd-Thread landet kurz vor diesem Reset.
 * Dieser Beobachter soll den Schreiber und den Zeitpunkt zeigen.
 *
 * **Was erfasst wird.** Jeder Schreibzugriff, den Compose an die globalen Beobachter meldet
 * (`Snapshot.registerGlobalWriteObserver`, derselbe Weg wie beim `GlobalSnapshotManager`). Aus dem
 * Main-Thread nur gezaehlt, aus jedem anderen Thread mit Stack aufgezeichnet (die letzten
 * [MAX_EREIGNISSE]). Die Phase sagt, in welchem Teil des Testlaufs das geschah.
 *
 * **Eine fruehere Sonde** (siehe [ZeitwaechterRegel]) versuchte einen Schreibbeobachter bei einer
 * `withFrameNanos`-Schleife und sah nichts - zu Recht, die Schleife schreibt keinen State. Hier geht
 * es um Schreibzugriffe.
 *
 * Der Beobachter wird je Sandbox genau einmal installiert und nie entfernt. Er ist billig (ein
 * Zaehler, fuer Fremd-Threads ein Stack), kann das Zeitverhalten aber nicht ganz unveraendert lassen.
 */
internal object GlobaleSchreiber {
    internal data class Ereignis(
        val zeitMs: Long,
        val phase: String,
        val thread: String,
        val zustand: String,
        val stack: List<String>,
    )

    private const val MAX_EREIGNISSE = 60
    private const val GEZEIGTE_EREIGNISSE = 15
    private const val GEZEIGTE_RAHMEN = 8

    private val installiert = AtomicBoolean(false)

    @Volatile
    private var phase: String = "vor dem ersten Test"
    private val proThread = ConcurrentHashMap<String, AtomicInteger>()
    private val fremde = ArrayDeque<Ereignis>()

    /** Idempotent; schlaegt die Installation fehl, bleibt die Diagnose schlicht ohne diesen Abschnitt. */
    fun installiere() {
        if (!installiert.compareAndSet(false, true)) return
        runCatching { Snapshot.registerGlobalWriteObserver(::beobachte) }
            .onFailure { installiert.set(false) }
    }

    fun setzePhase(neu: String) {
        phase = neu
    }

    /** Nur fuer Tests: leert Zaehler und Aufzeichnung, damit sie von frueheren Schreibzugriffen unabhaengig sind. */
    fun zuruecksetzen() {
        proThread.clear()
        synchronized(fremde) { fremde.clear() }
    }

    private fun beobachte(zustand: Any) {
        runCatching {
            val thread = Thread.currentThread()
            val gruppe = gruppenname(thread.name)
            proThread.getOrPut(gruppe) { AtomicInteger() }.incrementAndGet()
            if (thread.name.startsWith(MAIN_PRAEFIX)) return
            val ereignis =
                Ereignis(
                    zeitMs = System.nanoTime() / NANOS_PRO_MS,
                    phase = phase,
                    thread = thread.name,
                    zustand = zustand.javaClass.name.substringAfterLast('.'),
                    stack =
                        Throwable()
                            .stackTrace
                            .map { "${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
                            .dropWhile { istInterner(it) }
                            .take(GEZEIGTE_RAHMEN),
                )
            synchronized(fremde) {
                fremde.addLast(ereignis)
                while (fremde.size > MAX_EREIGNISSE) fremde.removeFirst()
            }
        }
    }

    /** Der Abschnitt der Diagnose; Zeiten sind relativ zum Aufruf (negativ = vor der Diagnose). */
    fun text(): String {
        val jetztMs = System.nanoTime() / NANOS_PRO_MS
        val z = StringBuilder()
        z.appendLine()
        z.appendLine("--- Globale Schreibzugriffe auf Compose-State (Beobachter, seit er in dieser Sandbox installiert wurde) ---")
        if (!installiert.get()) {
            z.appendLine("\tBeobachter nicht installiert")
            return z.toString()
        }
        val gesamt = proThread.values.sumOf { it.get() }
        val nachThread =
            proThread.entries
                .sortedWith(compareByDescending<Map.Entry<String, AtomicInteger>> { it.value.get() }.thenBy { it.key })
                .joinToString("; ") { "${it.value.get()} ${it.key}" }
        z.appendLine("\tinsgesamt $gesamt: $nachThread")
        val ereignisse = synchronized(fremde) { fremde.toList() }
        if (ereignisse.isEmpty()) {
            z.appendLine("\tkein Schreibzugriff aus einem anderen Thread als dem Main-Thread aufgezeichnet")
            return z.toString()
        }
        z.appendLine("\tAus fremden Threads (${ereignisse.size} aufgezeichnet, die letzten $GEZEIGTE_EREIGNISSE):")
        for (e in ereignisse.takeLast(GEZEIGTE_EREIGNISSE)) {
            val sekunden = "%.1f".format(Locale.ROOT, (e.zeitMs - jetztMs) / MS_PRO_S)
            z.appendLine("\t\t$sekunden s  [${e.phase}]  ${e.thread}  ${e.zustand}")
            e.stack.forEach { z.appendLine("\t\t\tat $it") }
        }
        return z.toString()
    }

    /** `pool-3-thread-7` und `pool-9-thread-1` werden eins; Robolectrics `SDK 34 Main Thread` bleibt lesbar. */
    private fun gruppenname(name: String): String = if (name.startsWith("SDK ")) name else name.replace(Regex("\\d+"), "#")

    /**
     * Die eigenen Rahmen und die der Compose-Runtime am Kopf des Stacks. Der Praefix der eigenen Klasse
     * wird an der Klassengrenze (`.` oder `$`) geschnitten - `GlobaleSchreiberTest` darf nicht darunter fallen.
     */
    private fun istInterner(rahmen: String): Boolean =
        rahmen.startsWith("$EIGENE_KLASSE.") ||
            rahmen.startsWith("$EIGENE_KLASSE$") ||
            rahmen.startsWith(RUNTIME_RAHMEN) ||
            rahmen.startsWith(THREAD_RAHMEN)

    private const val MAIN_PRAEFIX = "SDK "
    private const val EIGENE_KLASSE = "com.example.lrmprotokoll.testhilfen.GlobaleSchreiber"
    private const val RUNTIME_RAHMEN = "androidx.compose.runtime."
    private const val THREAD_RAHMEN = "java.lang.Thread."
    private const val NANOS_PRO_MS = 1_000_000L
    private const val MS_PRO_S = 1_000.0
}
