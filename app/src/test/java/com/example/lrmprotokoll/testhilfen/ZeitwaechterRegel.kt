package com.example.lrmprotokoll.testhilfen

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Nimmt die Stacks auf, WAEHREND ein Compose-Test auf Leerlauf wartet - kurz bevor Espresso
 * aufgibt.
 *
 * **Das Problem, das sie loest.** Der Leerlauf-Haenger aus
 * `docs/CI_FLAKINESS_UNTERSUCHUNG_BERICHT.md` Abschnitt 4.8 endet in einer
 * `AppNotIdleException`, deren Meldung wortwoertlich so aufhoert:
 *
 * ```
 * Compose did not get idle after 2161789 attempts in 60 SECONDS. ...
 * The following Idle Conditions failed .
 * ```
 *
 * Die Liste ist **leer**. Composes `getDiagnosticMessageIfBusy` liefert nichts zurueck, obwohl
 * `ComposeIdlingResource.isIdleNow` dauerhaft `false` sagt. Deshalb stand in jedem roten Lauf eine
 * Ausnahme ohne jede Angabe, was eigentlich beschaeftigt war - und deshalb war der Fehlschlag
 * monatelang nicht untersuchbar. Die Information war nur von aussen zu holen, per `jstack` am
 * laufenden Prozess. Diese Regel holt sie von innen.
 *
 * **Warum die Grenze unter 60 Sekunden liegt.** Espressos Master-Idling-Policy deckelt jede
 * Wartung bei 60 s; gemessen am 01.10.2026 fielen alle zehn Fehlschlaege eines roten Vollaufs
 * zwischen 60,1 und 60,7 s, auch die innerhalb von `setContent`. Ein Waechter mit einer Grenze
 * oberhalb davon wuerde also **nie** ausloesen. Mit [grenzeSekunden] = 50 schlaegt er zu, waehrend
 * die Wartung noch laeuft und der Stack noch etwas zeigt.
 *
 * Der Abstand nach unten ist gleichzeitig gross genug: die gesunden Tests dieses Repos laufen in
 * 0,1 bis 22 s, und Robolectrics erstmaliges Laden der nativen Laufzeit kostet rund 16 s
 * (`DefaultNativeRuntimeLoader.maybeCopyIcuData`, in einer Sonde gemessen). Ein ausgelasteter
 * Runner kann die Diagnose trotzdem einmal ohne echten Haenger ausloesen - das kostet ein paar
 * Zeilen im Bericht und macht keinen Test rot.
 *
 * **Warum nicht JUnits eigene Timeout-Regel.** Gemessen, nicht vermutet:
 * `Timeout.builder().withLookingForStuckThread(true)` fuehrt den Testrumpf ueber
 * `FailOnTimeout$CallableStatement` auf einem Fremdthread aus. Robolectric lehnt das ab -
 * `UnsupportedOperationException: main looper can only be controlled from main thread` -, und zwar
 * auch fuer gesunde Tests: in einer Sonde fielen beide, auch der, der nur `Text("Hallo")` rendert.
 * Diese Regel verschiebt den Test deshalb nicht, sie sieht nur zu.
 *
 * **Warum zwei Ausgabewege.** Erster Entwurf war `System.err` allein, mit der Begruendung, das
 * lande im CI-Joblog. Das traegt nicht: Gradle faengt die Stroeme der Testworker ab und haengt sie
 * an den XML-Bericht, der erst beim Abschluss der Aufgabe entsteht. Schneidet `Test.timeout` die
 * Aufgabe ab, ist die Diagnose weg. [DIAGNOSEDATEI] entsteht dagegen sofort und liegt unter
 * `app/build/`, das der CI-Schritt "Upload test reports" mit hochlaedt.
 *
 * **Was beschaeftigt ist, nicht nur dass.** Ein einzelner Stack zeigt nur die Schleife
 * (`isIdleNow` -> `advanceTimeByFrame`), nicht, wer die Frames anfordert. Deshalb zusaetzlich rund
 * 5 s Stichproben des Main-Threads, verdichtet auf App- und Compose-Rahmen. In einer Sonde mit
 * einer `withFrameNanos`-Endlosschleife zeigte das den Lambda der Schleife und
 * `Recomposer.performRecompose`. Ein Snapshot-Apply-/Schreib-Beobachter wurde ebenfalls versucht und
 * meldete in derselben Sonde **nichts** - er ist deshalb nicht enthalten.
 *
 * `rememberInfiniteTransition` allein haengt dagegen **nicht**: dieselbe Sonde mit einer
 * Endlosanimation wurde idle (Composes Testumgebung behandelt Endlosanimationen selbst).
 */
class ZeitwaechterRegel(
    private val grenzeSekunden: Long = 50,
) : TestRule {
    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        object : Statement() {
            override fun evaluate() {
                val testThread = Thread.currentThread()
                val fertig = CountDownLatch(1)
                val beobachter =
                    Thread {
                        if (fertig.await(grenzeSekunden, TimeUnit.SECONDS)) return@Thread
                        gibDiagnoseAus(description, testThread)
                    }
                beobachter.isDaemon = true
                beobachter.name = "Zeitwaechter"
                beobachter.start()
                try {
                    base.evaluate()
                } finally {
                    fertig.countDown()
                }
            }
        }

    private fun gibDiagnoseAus(
        description: Description,
        testThread: Thread,
    ) {
        val zeilen = StringBuilder()
        zeilen.appendLine()
        zeilen.appendLine("=== ZEITWAECHTER: ${description.className}.${description.methodName} ===")
        zeilen.appendLine("wartet seit ueber $grenzeSekunden s - Stacks folgen, der Test laeuft weiter.")
        // Der Testthread allein ist bei diesem Haenger nur die halbe Antwort: er steht in
        // setContent/waitForIdle. Der Leerdreh sitzt auf Robolectrics "SDK <n> Main Thread".
        for ((thread, stack) in Thread.getAllStackTraces()) {
            val wichtig =
                thread == testThread ||
                    thread.name.startsWith("SDK ") ||
                    thread.name == "Test worker"
            if (!wichtig) continue
            zeilen.appendLine()
            zeilen.appendLine("--- \"${thread.name}\" ${thread.state} ---")
            stack.take(GEZEIGTE_RAHMEN).forEach { zeilen.appendLine("\tat $it") }
            if (stack.size > GEZEIGTE_RAHMEN) {
                zeilen.appendLine("\t... ${stack.size - GEZEIGTE_RAHMEN} weitere Rahmen")
            }
        }
        haengeStichprobenAn(zeilen)
        zeilen.appendLine("=== ZEITWAECHTER Ende ===")

        System.err.print(zeilen)
        System.err.flush()
        runCatching {
            val datei = File(DIAGNOSEDATEI)
            datei.parentFile?.mkdirs()
            datei.appendText(zeilen.toString())
        }
    }

    /**
     * Nimmt [STICHPROBEN] Stacks des Robolectric-Main-Threads im Abstand von [STICHPROBEN_ABSTAND_MS]
     * auf und zaehlt, in wie vielen davon ein App-Rahmen oder ein Compose-Animationsrahmen steckt.
     * Ein einzelner Schnappschuss trifft fast immer nur die Schleife selbst.
     */
    private fun haengeStichprobenAn(zeilen: StringBuilder) {
        val mainThread = Thread.getAllStackTraces().keys.firstOrNull { it.name.startsWith("SDK ") && it.state == Thread.State.RUNNABLE }
        zeilen.appendLine()
        if (mainThread == null) {
            zeilen.appendLine("--- Stichproben: kein laufender \"SDK <n> Main Thread\" gefunden ---")
            return
        }
        val treffer = HashMap<String, Int>()
        repeat(STICHPROBEN) {
            mainThread.stackTrace
                .map { "${it.className}.${it.methodName}" }
                .filter(::istAussagekraeftig)
                .distinct()
                .forEach { treffer[it] = (treffer[it] ?: 0) + 1 }
            Thread.sleep(STICHPROBEN_ABSTAND_MS)
        }
        zeilen.appendLine("--- Stichproben \"${mainThread.name}\": $STICHPROBEN x alle $STICHPROBEN_ABSTAND_MS ms, Treffer je Rahmen ---")
        if (treffer.isEmpty()) zeilen.appendLine("\t(kein App- oder Animationsrahmen getroffen)")
        treffer.entries.sortedByDescending { it.value }.take(GEZEIGTE_STICHPROBEN_RAHMEN).forEach { (rahmen, anzahl) ->
            zeilen.appendLine("\t$anzahl  $rahmen")
        }
    }

    /** App- oder Compose-Rahmen, aber weder die Regel selbst noch der Testrumpf. */
    private fun istAussagekraeftig(rahmen: String): Boolean {
        if (INTERESSANTE_RAHMEN.none { rahmen.startsWith(it) }) return false
        if (AUSGENOMMENE_RAHMEN.any { rahmen.startsWith(it) }) return false
        return !rahmen.substringBeforeLast('.').endsWith("Test")
    }

    private companion object {
        const val GEZEIGTE_RAHMEN = 25
        const val STICHPROBEN = 100
        const val STICHPROBEN_ABSTAND_MS = 50L
        const val GEZEIGTE_STICHPROBEN_RAHMEN = 30

        /** App-Code und die Compose-Teile, die Frames anfordern koennen. */
        val INTERESSANTE_RAHMEN =
            listOf(
                "com.example.lrmprotokoll.",
                "androidx.compose.animation.",
                "androidx.compose.material3.",
                "androidx.compose.runtime.Recomposer",
            )

        /** Immer im Stack und damit ohne Aussage: die Regel selbst. */
        val AUSGENOMMENE_RAHMEN = listOf("com.example.lrmprotokoll.testhilfen.ZeitwaechterRegel")

        /**
         * Relativ zum Arbeitsverzeichnis der Testaufgabe, das Gradle auf das Modulverzeichnis
         * setzt - die Datei liegt damit unter `app/build/`.
         */
        const val DIAGNOSEDATEI = "build/zeitwaechter-diagnose.txt"
    }
}
