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
        zeilen.appendLine("=== ZEITWAECHTER Ende ===")

        System.err.print(zeilen)
        System.err.flush()
        runCatching {
            val datei = File(DIAGNOSEDATEI)
            datei.parentFile?.mkdirs()
            datei.appendText(zeilen.toString())
        }
    }

    private companion object {
        const val GEZEIGTE_RAHMEN = 25

        /**
         * Relativ zum Arbeitsverzeichnis der Testaufgabe, das Gradle auf das Modulverzeichnis
         * setzt - die Datei liegt damit unter `app/build/`.
         */
        const val DIAGNOSEDATEI = "build/zeitwaechter-diagnose.txt"
    }
}
