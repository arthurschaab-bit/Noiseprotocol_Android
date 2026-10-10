package com.example.lrmprotokoll.testhilfen

import android.os.Looper
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
                        gibDiagnoseAus(description, testThread, fertig)
                    }
                beobachter.isDaemon = true
                beobachter.name = "Zeitwaechter"
                beobachter.start()
                try {
                    base.evaluate()
                } finally {
                    fertig.countDown()
                    // Die Stichproben brechen mit dem Testende ab, schreiben aber noch ihren Block.
                    // Kurz darauf warten, damit die Diagnose vollstaendig ist, bevor der naechste
                    // Test im selben Prozess beginnt.
                    beobachter.join(ABSCHLUSS_WARTEZEIT_MS)
                }
            }
        }

    private fun gibDiagnoseAus(
        description: Description,
        testThread: Thread,
        fertig: CountDownLatch,
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
        // Die Stacks sofort wegschreiben, nicht erst nach den Stichproben: endet der Lauf in den
        // folgenden Sekunden (Espresso-Abbruch bei 60 s, Test.timeout), bleibt wenigstens das.
        schreibe(zeilen.toString())

        val stichproben = StringBuilder()
        haengeStichprobenAn(stichproben, testThread, fertig)
        stichproben.appendLine("=== ZEITWAECHTER Ende ===")
        schreibe(stichproben.toString())
    }

    private fun schreibe(text: String) {
        System.err.print(text)
        System.err.flush()
        runCatching {
            val datei = File(DIAGNOSEDATEI)
            datei.parentFile?.mkdirs()
            datei.appendText(text)
        }
    }

    /**
     * Nimmt bis zu [STICHPROBEN] Stacks des Testthreads im Abstand von [STICHPROBEN_ABSTAND_MS] auf
     * und zaehlt, in wie vielen davon ein App- oder Compose-Rahmen steckt. Ein einzelner
     * Schnappschuss trifft fast immer nur die Schleife selbst.
     *
     * Der Testthread IST unter Robolectric der "SDK <n> Main Thread", auf dem die Schleife
     * leerdreht (siehe die CI-Diagnosen). Er wird direkt genommen statt per Name und Zustand
     * gesucht: eine Momentaufnahme `RUNNABLE` verpasst ihn, wenn er gerade kurz wartet.
     *
     * Endet der Test waehrend der Stichproben, bricht die Schleife ab - sonst flossen die Rahmen
     * des naechsten Tests im selben Prozess in diese Zaehlung ein.
     */
    private fun haengeStichprobenAn(
        zeilen: StringBuilder,
        testThread: Thread,
        fertig: CountDownLatch,
    ) {
        val stacks = mutableListOf<Array<StackTraceElement>>()
        val warteschlangen = mutableListOf<List<String>>()
        for (i in 0 until STICHPROBEN) {
            if (fertig.count == 0L) break
            stacks += testThread.stackTrace
            leseHauptLooperWarteschlange()?.let { warteschlangen += it }
            if (fertig.await(STICHPROBEN_ABSTAND_MS, TimeUnit.MILLISECONDS)) break
        }
        zeilen.appendLine()
        zeilen.appendLine(
            "--- Stichproben \"${testThread.name}\": ${stacks.size} x alle $STICHPROBEN_ABSTAND_MS ms, Treffer je Rahmen ---",
        )
        // App-Rahmen getrennt und zuerst: sie sind der eigentliche Hinweis, und die vielen
        // Layout-/Mess-Rahmen aus androidx.compose.ui verdraengten sie sonst aus der Liste.
        val (app, compose) = zaehleRahmen(stacks).partition { it.first.startsWith(APP_PAKET) }
        zeilen.appendLine("App-Rahmen:")
        if (app.isEmpty()) zeilen.appendLine("\t(keiner getroffen)")
        app.take(GEZEIGTE_STICHPROBEN_RAHMEN).forEach { (rahmen, anzahl) -> zeilen.appendLine("\t$anzahl  $rahmen") }
        zeilen.appendLine("Compose-Rahmen:")
        if (compose.isEmpty()) zeilen.appendLine("\t(keiner getroffen)")
        compose.take(GEZEIGTE_STICHPROBEN_RAHMEN).forEach { (rahmen, anzahl) -> zeilen.appendLine("\t$anzahl  $rahmen") }
        haengeWarteschlangeAn(zeilen, warteschlangen)
    }

    /**
     * Die erste CI-Diagnose mit Stichproben (PR #269, 10.10.2026) zeigte in 100 Stichproben keinen
     * einzigen App- oder Compose-Rahmen; der Einzelstack stand in `Espresso.onIdle ->
     * ShadowPausedLooper.idle`. Die Wartung haengt also daran, dass der Main-Looper nie leer wird.
     * Wer dort laufend Nachrichten einstellt, zeigt nur die Warteschlange selbst - deshalb wird sie
     * bei jeder Stichprobe mitgelesen.
     */
    private fun haengeWarteschlangeAn(
        zeilen: StringBuilder,
        warteschlangen: List<List<String>>,
    ) {
        zeilen.appendLine()
        if (warteschlangen.isEmpty()) {
            zeilen.appendLine("--- Main-Looper-Warteschlange: nicht lesbar (kein Robolectric-Looper) ---")
            return
        }
        val schnitt = warteschlangen.sumOf { it.size }.toDouble() / warteschlangen.size
        zeilen.appendLine(
            "--- Main-Looper-Warteschlange: ${warteschlangen.size} Stichproben, im Schnitt " +
                "${"%.1f".format(java.util.Locale.ROOT, schnitt)} Nachrichten, in wie vielen Stichproben je Nachrichtenart ---",
        )
        val treffer = zaehleNachrichten(warteschlangen)
        if (treffer.isEmpty()) zeilen.appendLine("\t(Warteschlange in allen Stichproben leer)")
        treffer.take(GEZEIGTE_STICHPROBEN_RAHMEN).forEach { (art, anzahl) -> zeilen.appendLine("\t$anzahl  $art") }
    }

    internal companion object {
        private const val GEZEIGTE_RAHMEN = 25
        const val STICHPROBEN = 100
        const val STICHPROBEN_ABSTAND_MS = 50L
        const val GEZEIGTE_STICHPROBEN_RAHMEN = 30
        const val ABSCHLUSS_WARTEZEIT_MS = 1_000L
        const val MAX_NACHRICHTEN = 200

        const val APP_PAKET = "com.example.lrmprotokoll."

        /** App-Code und die Compose-Teile, die Frames anfordern koennen. */
        val INTERESSANTE_RAHMEN =
            listOf(
                APP_PAKET,
                "androidx.compose.animation.",
                "androidx.compose.foundation.",
                "androidx.compose.material3.",
                "androidx.compose.runtime.Recomposer",
                "androidx.compose.ui.",
            )

        /**
         * Immer im Stack und damit ohne Aussage: die Regel selbst und Composes Testumgebung. Letztere
         * stand in einer Sonde in 100 von 100 Stichproben und verdraengte die eigentlichen Rahmen.
         */
        val AUSGENOMMENE_RAHMEN =
            listOf(
                "com.example.lrmprotokoll.testhilfen.ZeitwaechterRegel",
                "androidx.compose.ui.test.",
            )

        /**
         * Relativ zum Arbeitsverzeichnis der Testaufgabe, das Gradle auf das Modulverzeichnis
         * setzt - die Datei liegt damit unter `app/build/`.
         */
        const val DIAGNOSEDATEI = "build/zeitwaechter-diagnose.txt"
    }
}

/**
 * Zaehlt je Rahmen (`Klasse.methode`), in wie vielen der [stacks] er vorkommt - jeder Stack zaehlt
 * einen Rahmen hoechstens einmal. Beruecksichtigt nur [istAussagekraeftig]e Rahmen, haeufigste
 * zuerst.
 */
internal fun zaehleRahmen(stacks: List<Array<StackTraceElement>>): List<Pair<String, Int>> {
    val treffer = HashMap<String, Int>()
    for (stack in stacks) {
        stack
            .map { "${it.className}.${it.methodName}" }
            .filter(::istAussagekraeftig)
            .distinct()
            .forEach { treffer[it] = (treffer[it] ?: 0) + 1 }
    }
    return treffer.entries.sortedByDescending { it.value }.map { it.key to it.value }
}

/** App- oder Compose-Rahmen, aber weder die Regel selbst noch der Testrumpf. */
internal fun istAussagekraeftig(rahmen: String): Boolean {
    if (ZeitwaechterRegel.INTERESSANTE_RAHMEN.none { rahmen.startsWith(it) }) return false
    if (ZeitwaechterRegel.AUSGENOMMENE_RAHMEN.any { rahmen.startsWith(it) }) return false
    return !rahmen.substringBeforeLast('.').endsWith("Test")
}

/**
 * Zaehlt je Nachrichtenart, in wie vielen Stichproben sie in der Warteschlange stand - jede
 * Stichprobe zaehlt eine Art hoechstens einmal. Haeufigste zuerst.
 */
internal fun zaehleNachrichten(warteschlangen: List<List<String>>): List<Pair<String, Int>> {
    val treffer = HashMap<String, Int>()
    for (warteschlange in warteschlangen) {
        warteschlange.distinct().forEach { treffer[it] = (treffer[it] ?: 0) + 1 }
    }
    return treffer.entries.sortedByDescending { it.value }.map { it.key to it.value }
}

/** `Foo$$Lambda/0x00007f...` -> `Foo$$Lambda`: die Adresse ist je Lauf anders und zerlegt die Zaehlung. */
internal fun ohneLambdaAdresse(klassenname: String): String = klassenname.substringBefore("/0x")

/**
 * Liest die wartenden Nachrichten des Main-Loopers, ohne sie anzufassen: je Nachricht die
 * Klasse ihres Callbacks, sonst Handler-Klasse und `what`. Ausserhalb von Robolectric (oder
 * wenn sich die Felder aendern) `null` - eine Diagnose darf nie selbst scheitern.
 *
 * Bewusst ohne Sperre gelesen, waehrend der Main-Thread weiterlaeuft: eine verkettete Liste,
 * deren Enden sich verschieben, liefert hoechstens eine leicht unscharfe Momentaufnahme. Die
 * Obergrenze [ZeitwaechterRegel.MAX_NACHRICHTEN] verhindert, dass eine dabei entstehende Schleife haengt.
 */
internal fun leseHauptLooperWarteschlange(): List<String>? =
    runCatching {
        val warteschlange = Looper.getMainLooper().queue
        val kopf = feld(warteschlange.javaClass, "mMessages").get(warteschlange)
        val ergebnis = mutableListOf<String>()
        var nachricht: Any? = kopf
        while (nachricht != null && ergebnis.size < ZeitwaechterRegel.MAX_NACHRICHTEN) {
            ergebnis += beschreibeNachricht(nachricht)
            nachricht = feld(nachricht.javaClass, "next").get(nachricht)
        }
        ergebnis
    }.getOrNull()

private fun beschreibeNachricht(nachricht: Any): String {
    val callback = feld(nachricht.javaClass, "callback").get(nachricht)
    if (callback != null) return "Runnable ${ohneLambdaAdresse(callback.javaClass.name)}"
    val ziel = feld(nachricht.javaClass, "target").get(nachricht)
    val what = feld(nachricht.javaClass, "what").get(nachricht)
    return "Handler ${ziel?.javaClass?.name?.let(::ohneLambdaAdresse) ?: "?"} what=$what"
}

private fun feld(
    klasse: Class<*>,
    name: String,
): java.lang.reflect.Field = klasse.getDeclaredField(name).apply { isAccessible = true }
