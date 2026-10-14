package com.example.lrmprotokoll.testhilfen

import androidx.compose.ui.test.junit4.ComposeTestRule
import java.util.Locale

/**
 * Liest von aussen, WORAUF Composes Testumgebung gerade wartet - die Antwort, die die
 * `AppNotIdleException` unter Robolectric nie gibt.
 *
 * **Warum das noetig ist.** `RobolectricIdlingStrategy.runUntilIdle` baut die Ausnahme mit
 * `AppNotIdleException.create(emptyList(), ...)`: die Liste der Gruende ist fest leer, und
 * `ComposeIdlingResource.getDiagnosticMessageIfBusy()` wird auf diesem Pfad nie aufgerufen
 * (Quelltext compose-ui-test 1.6.7 gelesen, Bytecode unabhaengig bestaetigt). Die Gruende stehen aber
 * in fuenf privaten Feldern von `ComposeIdlingResource`, die `isIdleNow` bei jedem Aufruf neu setzt.
 *
 * **Pfad** (in `IdlingGruendeSondeTest` unter Robolectric 4.16.1 nachgewiesen):
 * `AndroidComposeTestRule.environment` -> `AndroidComposeUiTestEnvironment.composeIdlingResource` ->
 * Felder [FELDER]. Alles Reflexion auf Interna; jede Stufe ist einzeln abgesichert, damit eine
 * Aenderung der Bibliothek die Diagnose schlechter macht, aber nie den Test.
 *
 * **Deutung der Felder** (Quelltext `ComposeIdlingResource.isIdleNow`): `isIdleNow` ist `false`, wenn
 * mindestens ein Frame gepumpt wurde (dann ist eines der ersten drei Felder wahr) ODER
 * `hadPendingSetContent` ODER `hadPendingMeasureLayout`. Gemessen: Eine handgeschriebene
 * `withFrameNanos`-Schleife erscheint als `hadRecomposerChanges`, nicht als
 * `hadAwaitersOnMainClock` - `Recomposer.hasPendingWork` schliesst eigene Frame-Wartende ein.
 */
internal object IdlingGruende {
    val FELDER =
        listOf(
            "hadAwaitersOnMainClock",
            "hadSnapshotChanges",
            "hadRecomposerChanges",
            "hadPendingSetContent",
            "hadPendingMeasureLayout",
        )

    private const val COMPOSE_REGEL = "androidx.compose.ui.test.junit4.AndroidComposeTestRule"

    /** Eine Momentaufnahme; [fehler] ist gesetzt, wenn ein Teil nicht lesbar war. */
    internal data class Momentaufnahme(
        val gruende: Map<String, Boolean>,
        val testzeitMs: Long?,
        val roots: List<String>?,
        val fehler: List<String>,
    )

    /**
     * Sucht in [zielobjekt] (der Testinstanz) das Feld mit der Compose-Testregel. `null`, wenn der
     * Test keine hat - dann ist die Diagnose schlicht nicht verfuegbar.
     */
    fun findeComposeRegel(zielobjekt: Any): Any? {
        var klasse: Class<*>? = zielobjekt.javaClass
        while (klasse != null && klasse != Any::class.java) {
            for (feld in klasse.declaredFields) {
                if (!istComposeRegel(feld.type)) continue
                val wert = runCatching { feld.apply { isAccessible = true }.get(zielobjekt) }.getOrNull()
                if (wert != null) return wert
            }
            klasse = klasse.superclass
        }
        return null
    }

    private fun istComposeRegel(typ: Class<*>): Boolean = generateSequence<Class<*>>(typ) { it.superclass }.any { it.name == COMPOSE_REGEL }

    fun lies(zielobjekt: Any?): Momentaufnahme {
        val fehler = mutableListOf<String>()
        val regel = zielobjekt?.let { findeComposeRegel(it) }
        if (regel == null) {
            return Momentaufnahme(emptyMap(), null, null, listOf("keine Compose-Testregel in der Testinstanz gefunden"))
        }

        val umgebung = leseFeld(regel, "environment")
        val ressource = umgebung?.let { leseFeld(it, "composeIdlingResource") }
        val gruende =
            if (ressource == null) {
                fehler += "ComposeIdlingResource nicht erreichbar (Pfad environment.composeIdlingResource)"
                emptyMap()
            } else {
                FELDER.associateWith { name ->
                    (leseFeld(ressource, name) as? Boolean) ?: false.also { fehler += "Feld $name nicht lesbar" }
                }
            }

        val testzeit =
            runCatching { (regel as ComposeTestRule).mainClock.currentTime }
                .onFailure { fehler += "Testuhr nicht lesbar: ${it.javaClass.simpleName}" }
                .getOrNull()
        val roots = umgebung?.let { leseRoots(it, fehler) }
        return Momentaufnahme(gruende, testzeit, roots, fehler)
    }

    /** Die registrierten Compose-Roots; das Popup eines Menues ist ein eigener Root. */
    private fun leseRoots(
        umgebung: Any,
        fehler: MutableList<String>,
    ): List<String>? {
        val registry =
            leseFeld(umgebung, "composeRootRegistry") ?: run {
                fehler += "composeRootRegistry nicht lesbar"
                return null
            }
        return runCatching {
            // `internal fun` wird mit Modulsuffix gemangelt (getRegisteredComposeRoots$ui_test_release).
            val methode =
                registry.javaClass.methods.first { it.name.startsWith("getRegisteredComposeRoots") }
            val roots = methode.invoke(registry) as Collection<*>
            roots.map { beschreibeRoot(it) }
        }.onFailure { fehler += "Roots nicht lesbar: ${it.javaClass.simpleName}" }.getOrNull()
    }

    private fun beschreibeRoot(root: Any?): String {
        if (root == null) return "null"
        val view = runCatching { root.javaClass.getMethod("getView").invoke(root) as android.view.View }.getOrNull()
        val offen = runCatching { root.javaClass.getMethod("getHasPendingMeasureOrLayout").invoke(root) }.getOrNull()
        val ansicht =
            view?.let {
                "${it.javaClass.simpleName} ${it.width}x${it.height} angehaengt=${it.isAttachedToWindow} " +
                    "sichtbarkeit=${it.visibility}"
            } ?: "Ansicht nicht lesbar"
        return "$ansicht messenOderLayoutOffen=$offen"
    }

    /** Liest ein (auch privates) Feld ueber die Klassenhierarchie; `null`, wenn es fehlt. */
    private fun leseFeld(
        objekt: Any,
        name: String,
    ): Any? {
        var klasse: Class<*>? = objekt.javaClass
        while (klasse != null) {
            val feld = runCatching { klasse!!.getDeclaredField(name) }.getOrNull()
            if (feld != null) return runCatching { feld.apply { isAccessible = true }.get(objekt) }.getOrNull()
            klasse = klasse.superclass
        }
        return null
    }
}

/**
 * Sammelt [IdlingGruende.Momentaufnahme]n waehrend der Stichproben und fasst sie zusammen:
 * je Grund, in wie vielen Stichproben er wahr war. Ein einzelner Wert taeuscht, weil `isIdleNow`
 * die Felder bei jedem Aufruf neu setzt; die Haeufigkeit zeigt, welcher Grund DAUERHAFT gilt.
 */
internal class IdlingStichproben {
    private val aufnahmen = mutableListOf<Pair<Long, IdlingGruende.Momentaufnahme>>()

    fun fuegeHinzu(
        wanduhrMs: Long,
        aufnahme: IdlingGruende.Momentaufnahme,
    ) {
        aufnahmen += wanduhrMs to aufnahme
    }

    fun text(): String {
        val z = StringBuilder()
        z.appendLine()
        if (aufnahmen.isEmpty()) {
            z.appendLine("--- Idling-Gruende: keine Stichprobe ---")
            return z.toString()
        }
        val lesbar = aufnahmen.filter { it.second.gruende.isNotEmpty() }
        z.appendLine(
            "--- Idling-Gruende (ComposeIdlingResource, ${lesbar.size} von ${aufnahmen.size} " +
                "Stichproben lesbar), in wie vielen Stichproben wahr ---",
        )
        if (lesbar.isEmpty()) {
            aufnahmen.flatMap { it.second.fehler }.distinct().forEach { z.appendLine("\tnicht lesbar: $it") }
        } else {
            for (feld in IdlingGruende.FELDER) {
                val wahr = lesbar.count { it.second.gruende[feld] == true }
                z.appendLine("\t${"%-26s".format(Locale.ROOT, feld)} $wahr / ${lesbar.size}")
            }
        }
        haengeTestuhrAn(z)
        val roots = aufnahmen.lastOrNull { it.second.roots != null }?.second?.roots
        if (roots != null) {
            z.appendLine("--- Compose-Roots (letzte Stichprobe): ${roots.size} ---")
            roots.forEach { z.appendLine("\t$it") }
        }
        return z.toString()
    }

    /**
     * Wie weit die VIRTUELLE Uhr in der REALEN Zeit gelaufen ist. Laeuft sie, pumpt die Schleife
     * Frames; steht sie, dreht etwas anderes leer.
     */
    private fun haengeTestuhrAn(z: StringBuilder) {
        val mitZeit = aufnahmen.filter { it.second.testzeitMs != null }
        if (mitZeit.size < 2) {
            z.appendLine("--- Testuhr: nicht lesbar oder zu wenige Stichproben ---")
            return
        }
        val (w0, a0) = mitZeit.first()
        val (w1, a1) = mitZeit.last()
        val virtuell = a1.testzeitMs!! - a0.testzeitMs!!
        val real = w1 - w0
        z.appendLine(
            "--- Testuhr: virtuell ${a0.testzeitMs} -> ${a1.testzeitMs} ms (+$virtuell ms) in $real ms Wanduhr ---",
        )
    }
}

/**
 * In welchem Zweig von `RobolectricIdlingStrategy.runUntilIdle` ein Stack steht. Die Schleife
 * wechselt zwischen: Looper leeren, Layout anfordern, `isIdleNow` fragen (das ggf. Frames pumpt).
 * Die Verteilung ueber die Stichproben trennt "Frames werden gepumpt" von "Layout steht aus".
 */
internal fun klassifiziereZweig(stack: Array<StackTraceElement>): String {
    val rahmen = stack.map { "${it.className}.${it.methodName}" }
    return when {
        rahmen.any { it.contains("AbstractMainTestClock.advanceDispatcher") } -> "Frames pumpen (advanceDispatcher)"
        rahmen.any { it.contains("ComposeIdlingResource.isIdleNow") } -> "isIdleNow (ohne Pump)"
        rahmen.any { it.contains("requestLayoutIfNeeded") } -> "Layout anfordern"
        rahmen.any { it.contains("Espresso") || it.contains("ShadowPausedLooper") } -> "Looper leeren (Espresso.onIdle)"
        rahmen.any { it.contains("RobolectricIdlingStrategy.runUntilIdle") } -> "runUntilIdle (sonst)"
        else -> "ausserhalb der Idle-Schleife"
    }
}

/**
 * Was der Scheduler beim Pumpen TATSAECHLICH ausfuehrt: Steht `TestCoroutineScheduler` im Stack
 * und darueber (naeher am Blatt) weitere Rahmen, laeuft darunter eine Aufgabe - deren erster Rahmen
 * wird gezaehlt. Steht der Scheduler selbst am Blatt, ist kein Aufgabencode im Spiel.
 *
 * Das beantwortet, was der Rahmenfilter allein nicht kann: ob beim Pumpen Compose-Arbeit laeuft
 * oder nur die Uhr weitergestellt wird.
 */
internal fun zaehleAufgabenImScheduler(stacks: List<Array<StackTraceElement>>): Pair<Int, List<Pair<String, Int>>> {
    var nurScheduler = 0
    val aufgaben = HashMap<String, Int>()
    for (stack in stacks) {
        val index = stack.indexOfFirst { it.className == "kotlinx.coroutines.test.TestCoroutineScheduler" }
        if (index < 0) continue
        if (index == 0) {
            nurScheduler++
        } else {
            val blatt = stack[0].let { "${ohneLambdaAdresse(it.className)}.${it.methodName}" }
            aufgaben[blatt] = (aufgaben[blatt] ?: 0) + 1
        }
    }
    val sortiert =
        aufgaben.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key to it.value }
    return nurScheduler to sortiert
}
