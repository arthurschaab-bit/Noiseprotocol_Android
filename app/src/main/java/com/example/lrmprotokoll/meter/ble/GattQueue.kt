package com.example.lrmprotokoll.meter.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

private const val DEFAULT_TIMEOUT_MS = 10_000L

/**
 * Serialisiert GATT-Operationen (Plan Abschnitt 5.2): Der Android-Bluetooth-Stack verarbeitet
 * immer nur eine Operation gleichzeitig und verwirft parallele writeCharacteristic/
 * writeDescriptor/requestMtu-Aufrufe stillschweigend - eine der haeufigsten Fehlerquellen in
 * BLE-Code ("funktioniert auf meinem Geraet, aber nicht auf deinem"). Jede Operation startet
 * erst, wenn die vorherige ueber ihren Callback abgeschlossen oder per Timeout verworfen wurde.
 *
 * [execute] laeuft auf einer Coroutine, [complete] dagegen auf dem GATT-Callback-Thread
 * (Binder). Der gemeinsame Zustand liegt deshalb hinter [lock]: ohne diese Synchronisation
 * gaebe es keine Happens-before-Beziehung zwischen Schreiben und Lesen, und der Callback
 * koennte eine veraltete Referenz sehen und die Completion verlieren.
 */
class GattQueue(private val timeoutMs: Long = DEFAULT_TIMEOUT_MS) {

    private val mutex = Mutex()
    private val lock = Any()
    private var pending: CompletableDeferred<Boolean>? = null

    /**
     * Wird gesetzt, sobald eine Operation ins Timeout gelaufen ist.
     *
     * Hintergrund: Ein verspaeteter Callback traegt keine Kennung, an der sich erkennen liesse,
     * zu welcher Operation er gehoert. Liefe nach einem Timeout einfach die naechste Operation
     * an, wuerde der Callback der alten sie faelschlich als erfolgreich quittieren - besonders
     * tueckisch beim CCCD-Write, weil die App dann auf Notifications wartet, die nie aktiviert
     * wurden.
     *
     * Deshalb gilt ein Timeout als fatal fuer diese Queue: Alle weiteren Operationen scheitern
     * sofort, bis [reset] sie wieder freigibt. Das entspricht der Vorgabe aus Plan Abschnitt
     * 5.2, nach einem Timeout die Verbindung neu aufzubauen statt weiterzumachen.
     */
    private var poisoned = false

    /**
     * Fuehrt [start] aus (typischerweise ein gatt.xxx()-Aufruf, der true zurueckgibt, wenn die
     * Operation vom Stack angenommen wurde) und wartet auf den zugehoerigen GATT-Callback, der
     * [complete] aufruft. Liefert false, wenn [start] das Absetzen bereits ablehnt, der Callback
     * einen Fehlerstatus meldet, [timeoutOverrideMs] verstreicht, oder die Queue nach einem
     * vorangegangenen Timeout gesperrt ist.
     *
     * Wenn [isCritical] false ist (z. B. bei optionalem MTU-Request), sperrt ein Timeout die Queue
     * nicht für nachfolgende Operationen wie Descriptor-Writes.
     */
    suspend fun execute(
        isCritical: Boolean = true,
        timeoutOverrideMs: Long = timeoutMs,
        start: () -> Boolean
    ): Boolean = mutex.withLock {
        val deferred = CompletableDeferred<Boolean>()
        synchronized(lock) {
            if (poisoned) return@withLock false
            pending = deferred
        }

        val accepted = start()
        if (!accepted) {
            synchronized(lock) { if (pending === deferred) pending = null }
            return@withLock false
        }

        val result = withTimeoutOrNull(timeoutOverrideMs) { deferred.await() }
        synchronized(lock) {
            if (pending === deferred) pending = null
            if (result == null && isCritical) poisoned = true
        }
        result ?: false
    }

    /**
     * Führt eine optionale GATT-Operation (z. B. requestMtu) mit kürzerem Timeout aus. Ein Timeout
     * oder Fehlschlag sperrt die Queue nicht für nachfolgende kritische Operationen.
     */
    suspend fun executeOptional(
        timeoutOverrideMs: Long = 1_000L,
        start: () -> Boolean
    ): Boolean = execute(isCritical = false, timeoutOverrideMs = timeoutOverrideMs, start = start)

    /**
     * Von der GATT-Callback-Methode aufzurufen, sobald die laufende Operation abgeschlossen ist.
     * Nimmt die wartende Operation heraus und erfuellt sie - ein Callback schliesst damit hoechstens
     * eine Operation ab, und ein zweiter Aufruf zur selben Operation laeuft ins Leere.
     */
    fun complete(success: Boolean) {
        val deferred = synchronized(lock) {
            val current = pending
            pending = null
            current
        }
        deferred?.complete(success)
    }

    /** Gibt die Queue nach einem Timeout wieder frei - beim Aufbau einer neuen Verbindung. */
    fun reset() {
        val deferred = synchronized(lock) {
            val current = pending
            pending = null
            poisoned = false
            current
        }
        deferred?.complete(false)
    }
}
