package com.example.lrmprotokoll.meter.ble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GattQueueTest {

    @Test
    fun completeMitErfolgLiefertTrue() = runTest {
        val queue = GattQueue()

        val resultDeferred = async {
            queue.execute { true }
        }
        runCurrent()
        queue.complete(true)

        assertTrue(resultDeferred.await())
    }

    @Test
    fun completeMitMisserfolgLiefertFalse() = runTest {
        val queue = GattQueue()

        val resultDeferred = async {
            queue.execute { true }
        }
        runCurrent()
        queue.complete(false)

        assertFalse(resultDeferred.await())
    }

    @Test
    fun abgelehnterStartLiefertSofortFalseOhneAufDenCallbackZuWarten() = runTest {
        val queue = GattQueue(timeoutMs = 10_000)

        val result = queue.execute { false }

        assertFalse(result)
    }

    @Test
    fun timeoutLiefertFalse() = runTest {
        val queue = GattQueue(timeoutMs = 1_000)

        val resultDeferred = async {
            queue.execute { true }
        }
        advanceTimeBy(1_100)
        runCurrent()

        assertFalse(resultDeferred.await())
    }

    @Test
    fun operationenWerdenSerialisiertNichtParallelGestartet() = runTest {
        val startedOrder = mutableListOf<Int>()
        val queue = GattQueue()

        val job1 = launch {
            queue.execute { startedOrder.add(1); true }
        }
        val job2 = launch {
            queue.execute { startedOrder.add(2); true }
        }
        runCurrent()

        // Nur die erste Operation darf gestartet sein, solange ihr Callback nicht kam - die
        // zweite wartet auf den Mutex, statt parallel loszulaufen.
        assertEquals(listOf(1), startedOrder)

        queue.complete(true)
        runCurrent()
        assertEquals(listOf(1, 2), startedOrder)

        queue.complete(true)
        job1.join()
        job2.join()
    }

    @Test
    fun nachTimeoutBleibtDieQueueBisZumResetGesperrt() = runTest {
        val queue = GattQueue(timeoutMs = 1_000)

        val a = async { queue.execute { true } }
        runCurrent()
        advanceTimeBy(1_500)
        runCurrent()
        assertFalse(a.await())

        // Gesperrt: start() darf gar nicht mehr aufgerufen werden.
        var started = false
        assertFalse(queue.execute { started = true; true })
        assertFalse("start() darf nach einem Timeout nicht mehr laufen", started)

        // Nach reset() arbeitet die Queue wieder normal.
        queue.reset()
        val c = async { queue.execute { true } }
        runCurrent()
        queue.complete(true)
        assertTrue(c.await())
    }

    @Test
    fun executeOptionalTimeoutSperrtDieQueueNicht() = runTest {
        val queue = GattQueue(timeoutMs = 10_000)

        val optionalDeferred = async { queue.executeOptional(timeoutOverrideMs = 500) { true } }
        runCurrent()
        advanceTimeBy(600)
        runCurrent()
        assertFalse("Optionaler Aufruf muss nach Timeout false liefern", optionalDeferred.await())

        // Nachfolgende kritische Operation darf NICHT gesperrt sein!
        val criticalDeferred = async { queue.execute { true } }
        runCurrent()
        queue.complete(true)
        assertTrue("Kritische Operation muss trotz optionalem Timeout erfolgreich sein", criticalDeferred.await())
    }
}
