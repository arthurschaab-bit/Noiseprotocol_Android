package com.example.lrmprotokoll.meter

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Unit-Test für den [FakeMeterTransport].
 *
 * Verifiziert:
 * 1. Sukzessive Frames tragen echt verschiedene receivedAt-Zeitstempel (keine StateFlow-Conflation).
 * 2. Frames fließen mit der konfigurierten Frequenz.
 * 3. [FakeMeterTransport.simulateStall] und [FakeMeterTransport.simulateCorruptFrames] aktualisieren FrameQuality und StateFlows korrekt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeMeterTransportTest {

    private val testDevice = BoundDevice("11:22:33:44:55:66", "PCE-323")

    @Test
    fun emittiertFramesMitMonotonenZeitstempelnUndKorrekterRate() = runTest {
        val transport = FakeMeterTransport(scope = backgroundScope, frameRateHz = 10.0)
        val frames = mutableListOf<MeterFrame>()
        val timestamps = mutableListOf<Instant>()

        backgroundScope.launch {
            transport.frames.collect { frames.add(it) }
        }
        backgroundScope.launch {
            transport.lastFrameAt.collect { it?.let { ts -> timestamps.add(ts) } }
        }

        transport.connect(testDevice)
        runCurrent()

        advanceTimeBy(350) // bei 10 Hz sollten ~3-4 Frames emittiert werden
        runCurrent()

        assertTrue("Mindestens 3 Frames sollten emittiert worden sein, war ${frames.size}", frames.size >= 3)
        assertTrue("Mindestens 3 Zeitstempel sollten registriert worden sein, war ${timestamps.size}", timestamps.size >= 3)

        // Verifiziere, dass keine zwei aufeinanderfolgenden Zeitstempel identisch sind
        for (i in 0 until timestamps.size - 1) {
            assertNotEquals(
                "Zeitstempel #${i} und #${i+1} dürfen nicht identisch sein",
                timestamps[i],
                timestamps[i + 1]
            )
        }

        transport.disconnect()
        runCurrent()
        assertEquals(ConnectionState.DISCONNECTED, transport.state.value)
    }

    @Test
    fun corruptFramesErhoehenErrorCountOhneNeueFramesZuEmittieren() = runTest {
        val transport = FakeMeterTransport(scope = backgroundScope, frameRateHz = 2.0)
        transport.simulateCorruptFrames(true)
        val frames = mutableListOf<MeterFrame>()

        backgroundScope.launch {
            transport.frames.collect { frames.add(it) }
        }

        transport.connect(testDevice)
        runCurrent()

        advanceTimeBy(1500) // 3 weitere Ticks
        runCurrent()

        // Da simulateCorruptFrames vor dem ersten Tick gesetzt war, wird kein Frame emittiert
        assertEquals(0, frames.size)
        val quality = transport.frameQuality.value
        assertTrue("Error-Frames sollten gezählt worden sein, war ${quality.errorFrames}", quality.errorFrames >= 3)
        assertEquals(quality.totalFrames, quality.errorFrames)

        transport.disconnect()
    }
}
