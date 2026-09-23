package com.example.lrmprotokoll.diagnose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O-8: [AnrWatchdog.pruefen] gegen Fake-Uhr und Fake-Main-Thread (AGENTS.md Abschnitt 3: keine
 * Mocking-Bibliothek). Der "Main-Thread" ist hier nur eine Liste geposteter Signale - der Test
 * entscheidet, ob und wann er sie abarbeitet, also ob der Main-Thread reagiert oder haengt.
 *
 * Plain JUnit: alle Android-Abhaengigkeiten (SystemClock, Looper) stecken nur in
 * Default-Parametern, die hier ersetzt werden.
 */
class AnrWatchdogTest {
    private var uhr = 0L
    private val offeneSignale = mutableListOf<Runnable>()
    private val befunde = mutableListOf<HaengerBefund>()
    private var erholungen = 0
    private val stack = arrayOf(StackTraceElement("com.example.Blockierer", "schlafe", "Blockierer.kt", 42))

    private val watchdog =
        AnrWatchdog(
            postAufMainThread = { offeneSignale.add(it) },
            mainThreadStacktrace = { stack },
            onHaenger = { befunde.add(it) },
            onErholt = { erholungen++ },
            uhrMs = { uhr },
            schwelleMs = 5_000L,
        )

    private fun mainThreadArbeitetAb() {
        offeneSignale.toList().forEach { it.run() }
        offeneSignale.clear()
    }

    /** Ein Takt des Watchdog-Threads: pruefen, dann [ms] Zeit vergehen lassen. */
    private fun takt(ms: Long = 1_000L) {
        watchdog.pruefen()
        uhr += ms
    }

    @Test
    fun reagierenderMainThreadWirdNieGemeldet() {
        repeat(30) {
            takt()
            mainThreadArbeitetAb()
        }

        assertTrue(befunde.isEmpty())
        assertEquals(0, erholungen)
    }

    @Test
    fun blockierterMainThreadWirdAbDerSchwelleGemeldet() {
        takt(4_999L) // Signal gepostet, Main-Thread arbeitet es nicht ab
        watchdog.pruefen()
        assertTrue("4999 ms liegen unter der Schwelle", befunde.isEmpty())

        uhr += 1L
        watchdog.pruefen()

        assertEquals(1, befunde.size)
        assertEquals(5_000L, befunde.single().dauerMs)
        assertEquals(
            "schlafe",
            befunde
                .single()
                .mainThreadStack
                .single()
                .methodName,
        )
    }

    @Test
    fun einHaengerWirdNurEinmalGemeldet() {
        repeat(30) { takt() }

        assertEquals(1, befunde.size)
        assertEquals("Solange der Main-Thread haengt, wird nicht nachgepostet", 1, offeneSignale.size)
    }

    @Test
    fun erholungWirdGemeldetUndDerNaechsteHaengerErneutErkannt() {
        repeat(6) { takt() }
        assertEquals(1, befunde.size)

        mainThreadArbeitetAb()
        takt()
        assertEquals(1, erholungen)

        repeat(6) { takt() }
        assertEquals(2, befunde.size)
    }

    @Test
    fun kurzeVerzoegerungUnterDerSchwelleIstKeinHaenger() {
        repeat(4) { takt() } // 4 s ohne Reaktion
        mainThreadArbeitetAb()
        repeat(10) {
            takt()
            mainThreadArbeitetAb()
        }

        assertTrue(befunde.isEmpty())
        assertEquals(0, erholungen)
    }
}
