package com.example.lrmprotokoll.testhilfen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaufzeitZustandTest {
    @Test
    fun dieMesswerteSindPlausibel() {
        val z = LaufzeitZustand.lies()

        assertTrue("JVM-Laufzeit", z.jvmLaufzeitMs > 0)
        assertTrue("Heap max", z.heapMaxMb > 0)
        assertTrue("Heap genutzt darf das Maximum nicht uebersteigen", z.heapGenutztMb <= z.heapMaxMb)
        assertTrue("Threads", z.threadAnzahl >= 1)
        assertTrue("Kerne", z.kerne >= 1)
        assertTrue("Threadgruppen", z.threadGruppen.sumOf { it.second } == z.threadAnzahl)
    }

    @Test
    fun threadnamenWerdenOhneZiffernZusammengefasstAberSdkBleibtGetrennt() {
        val gruppen =
            LaufzeitZustand.gruppiere(
                listOf("pool-3-thread-7", "pool-9-thread-1", "SDK 34 Main Thread", "SDK 29 Main Thread", "SDK 34 Main Thread"),
            )

        assertEquals(
            listOf("SDK 34 Main Thread" to 2, "pool-#-thread-#" to 2, "SDK 29 Main Thread" to 1),
            gruppen.sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first }),
        )
        assertTrue(gruppen.map { it.first }.containsAll(listOf("SDK 29 Main Thread", "SDK 34 Main Thread")))
    }

    @Test
    fun derTextNenntJedenAbschnitt() {
        val z = LaufzeitZustand.lies()

        val text = LaufzeitZustand.text(z, z)

        listOf("Zustand der Test-JVM", "JVM-Laufzeit", "Heap genutzt", "Garbage Collector", "Threads", "CPU-Kerne").forEach {
            assertTrue("$it fehlt: $text", text.contains(it))
        }
    }
}
