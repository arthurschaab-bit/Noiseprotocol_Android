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
 */
class GattQueue(private val timeoutMs: Long = DEFAULT_TIMEOUT_MS) {

    private val mutex = Mutex()
    private var pending: CompletableDeferred<Boolean>? = null

    /**
     * Fuehrt [start] aus (typischerweise ein gatt.xxx()-Aufruf, der true zurueckgibt, wenn die
     * Operation vom Stack angenommen wurde) und wartet auf den zugehoerigen GATT-Callback, der
     * [complete] aufruft. Liefert false, wenn [start] das Absetzen bereits ablehnt, der Callback
     * einen Fehlerstatus meldet, oder [timeoutMs] verstreicht.
     */
    suspend fun execute(start: () -> Boolean): Boolean = mutex.withLock {
        val deferred = CompletableDeferred<Boolean>()
        pending = deferred
        val accepted = start()
        if (!accepted) {
            pending = null
            return@withLock false
        }
        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
        pending = null
        result ?: false
    }

    /** Von der GATT-Callback-Methode aufzurufen, sobald die laufende Operation abgeschlossen ist. */
    fun complete(success: Boolean) {
        pending?.complete(success)
    }
}
