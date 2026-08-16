package com.example.lrmprotokoll.meter

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FakeMeterTransportTest {

    private val device = BoundDevice(address = "AA:BB:CC:DD:EE:FF", name = "PCE-323")

    @Test
    fun connectErreichtStreamingUndLiefertFramesMit2Hz() = runTest {
        val transport = FakeMeterTransport(scope = backgroundScope, frameRateHz = 2.0)
        val received = mutableListOf<MeterFrame>()
        backgroundScope.launch { transport.frames.collect { received.add(it) } }

        transport.connect(device)
        advanceTimeBy(2_100)
        runCurrent()

        assertEquals(ConnectionState.STREAMING, transport.state.value)
        assertTrue("erwartet mehrere Frames, war ${received.size}", received.size >= 3)
        received.forEach { assertTrue(it.level in 20.0..140.0) }
    }

    @Test
    fun disconnectStopptFramesUndSetztDisconnected() = runTest {
        val transport = FakeMeterTransport(scope = backgroundScope, frameRateHz = 2.0)
        val received = mutableListOf<MeterFrame>()
        backgroundScope.launch { transport.frames.collect { received.add(it) } }

        transport.connect(device)
        advanceTimeBy(1_000)
        runCurrent()
        transport.disconnect()
        val countAtDisconnect = received.size
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(ConnectionState.DISCONNECTED, transport.state.value)
        assertEquals(countAtDisconnect, received.size)
    }

    @Test
    fun simulateConnectionLossStopptFramesOhneExplizitenDisconnect() = runTest {
        val transport = FakeMeterTransport(scope = backgroundScope, frameRateHz = 2.0)
        transport.connect(device)
        advanceTimeBy(500)
        runCurrent()

        transport.simulateConnectionLoss()

        assertEquals(ConnectionState.DISCONNECTED, transport.state.value)
    }

    @Test
    fun simulateStallHaeltFramesZurueckOhneZustandZuAendern() = runTest {
        val transport = FakeMeterTransport(scope = backgroundScope, frameRateHz = 2.0)
        val received = mutableListOf<MeterFrame>()
        backgroundScope.launch { transport.frames.collect { received.add(it) } }

        transport.connect(device)
        advanceTimeBy(1_000)
        runCurrent()
        val countBeforeStall = received.size

        transport.simulateStall(true)
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(ConnectionState.STREAMING, transport.state.value)
        assertEquals(countBeforeStall, received.size)

        transport.simulateStall(false)
        advanceTimeBy(1_000)
        runCurrent()

        assertTrue(received.size > countBeforeStall)
    }

    @Test
    fun simulateCorruptFramesLiefertUnplausibleWerte() = runTest {
        val transport = FakeMeterTransport(scope = backgroundScope, frameRateHz = 2.0)
        val received = mutableListOf<MeterFrame>()
        backgroundScope.launch { transport.frames.collect { received.add(it) } }

        transport.connect(device)
        transport.simulateCorruptFrames(true)
        advanceTimeBy(1_500)
        runCurrent()

        assertTrue(received.isNotEmpty())
        assertTrue(received.all { it.level !in 20.0..140.0 })
    }

    @Test
    fun sendOhneVerbindungSchlaegtFehl() = runTest {
        val transport = FakeMeterTransport(scope = backgroundScope, frameRateHz = 2.0)

        val result = transport.send(MeterCommand.ToggleWeightFrequency)

        assertTrue(result.isFailure)
    }
}
