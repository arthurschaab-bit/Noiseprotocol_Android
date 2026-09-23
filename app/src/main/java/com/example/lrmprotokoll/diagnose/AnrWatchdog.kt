package com.example.lrmprotokoll.diagnose

import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/** Dateiname fuer den Stacktrace-Mitschnitt des ANR-Watchdogs (O-8, Konzept Abschnitt 8a). */
const val ANR_WATCHDOG_DATEINAME = "anr_watchdog.txt"

/** Owner-Entscheidung O-8 (23.09.2026): 5 s, wie Androids eigene Grenze fuer den Eingabe-ANR. */
internal const val ANR_WATCHDOG_SCHWELLE_MS = 5_000L

/** Wie oft geprueft wird - bestimmt, wie weit die Erkennung hinter der Schwelle liegen kann. */
internal const val ANR_WATCHDOG_PRUEFINTERVALL_MS = 1_000L

/** Ein erkannter Haenger: wie lange der Main-Thread schon nicht reagiert, und wo er steht. */
class HaengerBefund(
    val dauerMs: Long,
    val mainThreadStack: Array<StackTraceElement>,
)

/**
 * O-8 (Konzept Abschnitt 8a/8b): erkennt einen blockierten Main-Thread selbst, statt auf das
 * System zu warten. Noetig, weil `ApplicationExitInfo` (und damit der System-ANR-Trace) erst ab
 * Android 11 existiert - auf Android 10, dem Owner-Geraet Huawei P30, gab es ohne diesen
 * Watchdog von einem ANR keine Spur. Laeuft bewusst auf allen Versionen: auch ab Android 11
 * meldet das System einen ANR nur, wenn es den Prozess beendet, nicht einen Haenger, von dem
 * sich die App wieder erholt.
 *
 * Prinzip: ein eigener Thread legt ein kleines Signal in die Main-Thread-Warteschlange und
 * prueft im [ANR_WATCHDOG_PRUEFINTERVALL_MS]-Takt, ob es abgearbeitet wurde. Liegt es laenger
 * als [schwelleMs], gilt der Main-Thread als haengend: einmal [onHaenger] je Haenger, einmal
 * [onErholt], sobald er wieder reagiert. Beide Callbacks laufen im Watchdog-Thread.
 *
 * Gemessen wird mit [SystemClock.uptimeMillis] - die Uhr steht im Tiefschlaf des Geraets still,
 * ein schlafendes Geraet ist also kein Haenger. Auch mit angehaengtem Debugger wird gemeldet
 * (Owner-Entscheidung O-8, 23.09.2026) - ein Haltepunkt erzeugt dann bewusst einen Befund.
 */
class AnrWatchdog(
    private val postAufMainThread: (Runnable) -> Unit,
    private val mainThreadStacktrace: () -> Array<StackTraceElement>,
    private val onHaenger: (HaengerBefund) -> Unit,
    private val onErholt: () -> Unit,
    private val uhrMs: () -> Long = { SystemClock.uptimeMillis() },
    private val schwelleMs: Long = ANR_WATCHDOG_SCHWELLE_MS,
    private val pruefintervallMs: Long = ANR_WATCHDOG_PRUEFINTERVALL_MS,
) {
    /** Uptime, zu der das noch nicht abgearbeitete Signal gepostet wurde; -1 = keins offen. */
    private val signalGepostetUm = AtomicLong(-1L)

    /** Nur vom Watchdog-Thread gelesen und geschrieben. */
    private var haengerGemeldet = false

    @Volatile private var thread: Thread? = null

    val laeuft: Boolean get() = thread != null

    /**
     * Ein Pruefschritt. Testseam: die Tests treiben die Logik hierueber mit Fake-Uhr und
     * Fake-Main-Thread, ohne echten Thread.
     */
    internal fun pruefen() {
        val jetzt = uhrMs()
        val gepostetUm = signalGepostetUm.get()
        if (gepostetUm < 0) {
            if (haengerGemeldet) {
                haengerGemeldet = false
                onErholt()
            }
            signalGepostetUm.set(jetzt)
            postAufMainThread(Runnable { signalGepostetUm.set(-1L) })
        } else if (!haengerGemeldet && jetzt - gepostetUm >= schwelleMs) {
            haengerGemeldet = true
            onHaenger(HaengerBefund(dauerMs = jetzt - gepostetUm, mainThreadStack = mainThreadStacktrace()))
        }
    }

    @Synchronized
    fun start() {
        if (thread != null) return
        thread =
            Thread({
                while (!Thread.currentThread().isInterrupted) {
                    // Ein Fehler im Callback darf den Watchdog nicht beenden.
                    runCatching { pruefen() }.onFailure { Log.w(TAG, "Pruefschritt fehlgeschlagen", it) }
                    try {
                        Thread.sleep(pruefintervallMs)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
            }, "AnrWatchdog").apply {
                isDaemon = true
                start()
            }
    }

    @Synchronized
    fun stop() {
        thread?.interrupt()
        thread = null
    }

    companion object {
        private const val TAG = "AnrWatchdog"

        /** Der echte Main-Thread-Stacktrace, fuer [mainThreadStacktrace]. */
        fun echterMainThreadStack(): Array<StackTraceElement> = Looper.getMainLooper().thread.stackTrace
    }
}
