package com.example.lrmprotokoll.testhilfen

import java.util.Locale

/**
 * Zustand der Test-JVM, in der ALLE rund 1400 Tests nacheinander laufen (kein `maxParallelForks`,
 * kein `forkEvery`, siehe `app/build.gradle.kts`). Untersuchung AppNotIdle, PR #280, Hypothese C-H8:
 * In einer langlaufenden JVM, in der Robolectric mehrere Sandboxes haelt, koennten Speicherdruck
 * oder Thread-Reste mit der Laufzeit wachsen. Das waere eine Erklaerung fuer "der Haenger beginnt
 * erst nach einigen Minuten", "ein schneller lokaler Rechner zeigt ihn nicht" und "mal trifft es
 * einen, mal vierzehn Tests". Belegt ist davon nichts; diese Klasse macht es messbar.
 *
 * `java.lang.management` wird per Reflexion gelesen: Unit-Tests kompilieren gegen die
 * Android-Plattform-API, die dieses Paket nicht kennt, die JVM zur Laufzeit aber schon. Ist etwas
 * nicht lesbar, steht der Wert als "nicht lesbar" in der Diagnose - sie bricht nie ab.
 */
internal object LaufzeitZustand {
    /** `-1` bzw. `null` heissen: nicht lesbar. */
    internal data class Momentaufnahme(
        val jvmLaufzeitMs: Long,
        val heapGenutztMb: Long,
        val heapMaxMb: Long,
        val gcAnzahl: Long,
        val gcMs: Long,
        val threadAnzahl: Int,
        val threadGruppen: List<Pair<String, Int>>,
        val kerne: Int,
        val systemLast: Double?,
    )

    private const val MX = "java.lang.management."

    fun lies(): Momentaufnahme {
        val runtime = Runtime.getRuntime()
        val threads = Thread.getAllStackTraces().keys
        val gcBeans = (mxFabrik("getGarbageCollectorMXBeans") as? List<*>).orEmpty()
        return Momentaufnahme(
            jvmLaufzeitMs = mxWert(mxFabrik("getRuntimeMXBean"), "RuntimeMXBean", "getUptime") as? Long ?: -1L,
            heapGenutztMb = (runtime.totalMemory() - runtime.freeMemory()) / MB,
            heapMaxMb = runtime.maxMemory() / MB,
            gcAnzahl = gcBeans.sumOf { (mxWert(it, "GarbageCollectorMXBean", "getCollectionCount") as? Long ?: 0L).coerceAtLeast(0L) },
            gcMs = gcBeans.sumOf { (mxWert(it, "GarbageCollectorMXBean", "getCollectionTime") as? Long ?: 0L).coerceAtLeast(0L) },
            threadAnzahl = threads.size,
            threadGruppen = gruppiere(threads.map { it.name }),
            kerne = runtime.availableProcessors(),
            systemLast =
                (mxWert(mxFabrik("getOperatingSystemMXBean"), "OperatingSystemMXBean", "getSystemLoadAverage") as? Double)
                    ?.takeIf { it >= 0 },
        )
    }

    private fun mxFabrik(methode: String): Any? =
        runCatching { Class.forName(MX + "ManagementFactory").getMethod(methode).invoke(null) }.getOrNull()

    /** Ruft die Methode ueber die oeffentliche Schnittstelle auf, nicht ueber die interne Klasse. */
    private fun mxWert(
        objekt: Any?,
        schnittstelle: String,
        methode: String,
    ): Any? = runCatching { objekt?.let { Class.forName(MX + schnittstelle).getMethod(methode).invoke(it) } }.getOrNull()

    /**
     * Fasst Threadnamen zusammen, indem Ziffern entfernt werden (`SDK 34 Main Thread` und
     * `SDK 29 Main Thread` bleiben getrennt, `pool-3-thread-7` und `pool-9-thread-1` werden eins).
     * Haeufigste zuerst, bei Gleichstand alphabetisch - sonst unterscheiden sich zwei Diagnosen
     * desselben Haengers allein durch die HashMap-Reihenfolge.
     */
    fun gruppiere(namen: List<String>): List<Pair<String, Int>> =
        namen
            .groupingBy { name -> if (name.startsWith("SDK ")) name else name.replace(Regex("\\d+"), "#") }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key to it.value }

    /** Anfang und Ende der Stichproben: Aenderungen sagen mehr als ein einzelner Wert. */
    fun text(
        anfang: Momentaufnahme,
        ende: Momentaufnahme,
    ): String {
        val z = StringBuilder()
        z.appendLine()
        z.appendLine("--- Zustand der Test-JVM (alle Tests laufen in dieser einen JVM) ---")
        z.appendLine("\tJVM-Laufzeit:   ${minuten(anfang.jvmLaufzeitMs)} min (Ende der Stichproben: ${minuten(ende.jvmLaufzeitMs)} min)")
        z.appendLine("\tHeap genutzt:   ${anfang.heapGenutztMb} MB -> ${ende.heapGenutztMb} MB von maximal ${ende.heapMaxMb} MB")
        z.appendLine(
            "\tGarbage Collector: ${ende.gcAnzahl} Laeufe, ${ende.gcMs} ms gesamt; " +
                "waehrend der Stichproben +${ende.gcAnzahl - anfang.gcAnzahl} Laeufe, +${ende.gcMs - anfang.gcMs} ms",
        )
        z.appendLine("\tThreads:        ${anfang.threadAnzahl} -> ${ende.threadAnzahl}")
        val last = ende.systemLast?.let { "%.2f".format(Locale.ROOT, it) } ?: "nicht lesbar"
        z.appendLine("\tCPU-Kerne:      ${anfang.kerne}, Systemlast $last")
        z.appendLine("\tThreads nach Namen (Ziffern zusammengefasst):")
        ende.threadGruppen.take(GEZEIGTE_GRUPPEN).forEach { (name, anzahl) -> z.appendLine("\t\t$anzahl  $name") }
        return z.toString()
    }

    private fun minuten(ms: Long): String = if (ms < 0) "nicht lesbar" else "%.1f".format(Locale.ROOT, ms / 60_000.0)

    private const val MB = 1024L * 1024L
    private const val GEZEIGTE_GRUPPEN = 12
}
