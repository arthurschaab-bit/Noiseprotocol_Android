package com.example.lrmprotokoll.meter

import java.time.Duration
import java.time.Instant
import kotlin.random.Random
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROMPT_M3 Aufgabe 1+2: Tests fuer den Verbindungs-Zustandsautomaten. Jeder Testfall aus dem
 * Prompt ist einzeln abgedeckt; wo sinnvoll bilden zwei Tests bewusst ein Gegentest-Paar, das
 * beide Seiten einer Schwelle prueft (z.B. hoheFehlerrateFuehrtZuDegraded vs.
 * einzelnerFehlerFrameLoestKeinSofortigesDegradedAus fuer die Fehlerraten-Schwelle,
 * nachErschoepftenVersuchenWirdFailedGemeldet vs. flatterndeVerbindungFuehrtNichtZuFailed fuer
 * den Fehlschlagszaehler) - so faellt der jeweils andere Test durch, wenn eine der beiden Seiten
 * der Logik entfernt oder falsch verschoben wird.
 *
 * Alle Zeiten laufen ueber die virtuelle Zeit von [runTest]: sowohl [ConnectionSupervisor] als
 * auch [FakeMeterTransport] werden mit [TestScope.backgroundScope] konstruiert, die injizierte
 * [InstantSource] liest [TestScope.testScheduler], damit das eigene Fehlerraten-Zeitfenster
 * synchron zur selben virtuellen Uhr laeuft. WICHTIG: sobald ein Transport aktiv Frames sendet
 * oder wiederholt neu verbindet, plant er sich per delay() immer weiter neu ein -
 * [kotlinx.coroutines.test.advanceUntilIdle] wuerde dann nie "idle" werden. Deshalb wird hier
 * durchgehend [advanceTimeBy] mit konkreten, aus dem erwarteten Verhalten hergeleiteten
 * Obergrenzen verwendet, gefolgt von [runCurrent] fuer alles genau an der Zeitgrenze.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionSupervisorTest {

    private val device = BoundDevice(address = "AA:BB:CC:DD:EE:FF", name = "PCE-323")

    private fun TestScope.newTransport(frameRateHz: Double = 2.0): FakeMeterTransport =
        FakeMeterTransport(scope = backgroundScope, frameRateHz = frameRateHz)

    private fun TestScope.newSupervisor(
        transport: MeterTransport,
        adapterEnabled: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow(),
        staleAfter: Duration = Duration.ofSeconds(5),
        errorRateWindow: Duration = Duration.ofSeconds(30),
        maxAttempts: Int = 8,
    ): ConnectionSupervisor {
        val clock = InstantSource { Instant.EPOCH.plusMillis(testScheduler.currentTime) }
        return ConnectionSupervisor(
            transport = transport,
            scope = backgroundScope,
            adapterEnabled = adapterEnabled,
            now = clock,
            random = Random(1),
            staleAfter = staleAfter,
            errorRateWindow = errorRateWindow,
            maxAttempts = maxAttempts,
        )
    }

    private fun TestScope.observeStates(supervisor: ConnectionSupervisor): List<ConnectionState> {
        val states = mutableListOf<ConnectionState>()
        backgroundScope.launch { supervisor.state.collect { states.add(it) } }
        return states
    }

    /** Verbindet nie - simuliert ein Geraet ausser Reichweite, um den Backoff isoliert zu messen. */
    private class NeverStreamingTransport(private val scheduler: TestCoroutineScheduler) : MeterTransport {
        private val _state = MutableStateFlow(ConnectionState.IDLE)
        override val state: StateFlow<ConnectionState> = _state.asStateFlow()
        override val frames: SharedFlow<MeterFrame> = MutableSharedFlow()
        private val _lastFrameAt = MutableStateFlow<Instant?>(null)
        override val lastFrameAt: StateFlow<Instant?> = _lastFrameAt.asStateFlow()
        private val _frameQuality = MutableStateFlow(FrameQuality())
        override val frameQuality: StateFlow<FrameQuality> = _frameQuality.asStateFlow()

        val connectTimestampsMillis = mutableListOf<Long>()

        override suspend fun connect(device: BoundDevice) {
            connectTimestampsMillis.add(scheduler.currentTime)
            _state.value = ConnectionState.CONNECTING
        }

        override suspend fun disconnect() {
            _state.value = ConnectionState.DISCONNECTED
        }

        override suspend fun send(command: MeterCommand): Result<Unit> = Result.success(Unit)
    }

    private fun assertBackoffGap(expectedSeconds: Long, actualMillis: Long, label: String) {
        val expectedMillis = expectedSeconds * 1000
        val lower = (expectedMillis * 0.8).toLong() - 30
        val upper = (expectedMillis * 1.2).toLong() + 30
        assertTrue(
            "$label: erwartet ${expectedMillis}ms ±20%, war ${actualMillis}ms",
            actualMillis in lower..upper
        )
    }

    @Test
    fun verbindungsverlustFuehrtUeberReconnectingZurueckZuStreaming() = runTest {
        val transport = newTransport()
        val supervisor = newSupervisor(transport)

        supervisor.start(device)
        runCurrent()
        assertEquals(ConnectionState.STREAMING, supervisor.state.value)

        transport.simulateConnectionLoss()
        runCurrent()
        assertEquals(ConnectionState.RECONNECTING, supervisor.state.value)

        advanceTimeBy(1_300) // erster Backoff-Schritt: 1s ± 20% = max. 1,2s
        runCurrent()
        assertEquals(ConnectionState.STREAMING, supervisor.state.value)
    }

    @Test
    fun backoffFolgtDefinierterSequenzMitJitter() = runTest {
        val transport = NeverStreamingTransport(testScheduler)
        // maxAttempts = 9: durchlaeuft alle 6 Backoff-Stufen plus zwei weitere im konstanten
        // 60s-Takt, ohne vorher FAILED zu erreichen - liefert 8 messbare Luecken.
        val supervisor = newSupervisor(
            transport,
            staleAfter = Duration.ofMillis(1),
            maxAttempts = 9,
        )

        supervisor.start(device)
        runCurrent()
        // Obergrenze fuer 8 Luecken (1+2+4+8+16+30+60+60=181s) mit max. +20% Jitter, plus Puffer.
        advanceTimeBy(220_000)
        runCurrent()

        val timestamps = transport.connectTimestampsMillis
        assertEquals(9, timestamps.size)
        val gaps = timestamps.zipWithNext { a, b -> b - a }
        val expectedSeconds = longArrayOf(1, 2, 4, 8, 16, 30, 60, 60)
        gaps.forEachIndexed { index, gap ->
            assertBackoffGap(expectedSeconds[index], gap, "Luecke #${index + 1}")
        }
        assertEquals(ConnectionState.FAILED, supervisor.state.value)
    }

    @Test
    fun nachErschoepftenVersuchenWirdFailedGemeldet() = runTest {
        val transport = NeverStreamingTransport(testScheduler)
        val supervisor = newSupervisor(transport, staleAfter = Duration.ofMillis(1), maxAttempts = 3)

        supervisor.start(device)
        runCurrent()
        advanceTimeBy(5_000) // deckt die 2 Luecken (1s, 2s) mit max. +20% Jitter ab
        runCurrent()

        assertEquals(ConnectionState.FAILED, supervisor.state.value)
        assertEquals(3, transport.connectTimestampsMillis.size)
    }

    @Test
    fun stallFuehrtNachTStaleZuDegradedUndErholtSichNachEndeDesStalls() = runTest {
        val transport = newTransport()
        val supervisor = newSupervisor(transport, staleAfter = Duration.ofSeconds(5))
        val states = observeStates(supervisor)

        supervisor.start(device)
        runCurrent()
        assertEquals(ConnectionState.STREAMING, supervisor.state.value)

        transport.simulateStall(true)
        advanceTimeBy(5_100) // > t_stale
        runCurrent()
        assertTrue("Stall haette DEGRADED ausloesen muessen, war $states", states.contains(ConnectionState.DEGRADED))

        // Stall vor dem automatischen Reconnect beenden, sonst wuerde die neue Verbindung
        // (stalled bleibt sonst gesetzt) sofort wieder in denselben Stillstand laufen.
        transport.simulateStall(false)
        advanceTimeBy(1_500) // deckt den ersten Backoff-Schritt (max. 1,2s) plus Verbindungsaufbau ab
        runCurrent()
        assertEquals(ConnectionState.STREAMING, supervisor.state.value)
    }

    @Test
    fun adapterAusPausiertUndAdapterAnStartetSofortNeu() = runTest {
        val transport = newTransport()
        val adapterEnabled = MutableStateFlow(true)
        val supervisor = newSupervisor(transport, adapterEnabled = adapterEnabled)

        supervisor.start(device)
        runCurrent()
        assertEquals(ConnectionState.STREAMING, supervisor.state.value)

        adapterEnabled.value = false
        runCurrent()
        assertEquals(ConnectionState.DISCONNECTED, supervisor.state.value)

        // Waehrend der Adapter aus ist, darf kein weiterer Verbindungsversuch stattfinden -
        // auch nicht nach langer Wartezeit.
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(ConnectionState.DISCONNECTED, supervisor.state.value)

        adapterEnabled.value = true
        runCurrent() // sofortige Wiederaufnahme ohne Backoff-Wartezeit
        assertEquals(ConnectionState.STREAMING, supervisor.state.value)
    }

    @Test
    fun hoheFehlerrateFuehrtZuDegraded() = runTest {
        val transport = newTransport()
        val supervisor = newSupervisor(transport, errorRateWindow = Duration.ofSeconds(30))
        val states = observeStates(supervisor)

        supervisor.start(device)
        runCurrent()
        assertEquals(ConnectionState.STREAMING, supervisor.state.value)

        transport.simulateCorruptFrames(true)
        advanceTimeBy(3_000) // 6 Ticks bei 2 Hz, mehr als die fuer die Mindeststichprobe noetigen 5
        runCurrent()

        assertTrue(
            "Hohe Fehlerrate haette DEGRADED ausloesen muessen, war $states",
            states.contains(ConnectionState.DEGRADED)
        )
    }

    @Test
    fun einzelnerFehlerFrameLoestKeinSofortigesDegradedAus() = runTest {
        // Gegentest zu hoheFehlerrateFuehrtZuDegraded: ohne Mindeststichprobe/Zeitfenster
        // wuerde ein einzelner Fehler-Frame (1 von 1 = 100%) sofort DEGRADED ausloesen und
        // einen weiteren Reconnect erzwingen - genau die vom Prompt gewarnte Selbstbefeuerung.
        val transport = newTransport()
        val supervisor = newSupervisor(transport)
        val states = observeStates(supervisor)

        supervisor.start(device)
        runCurrent()
        assertEquals(ConnectionState.STREAMING, supervisor.state.value)

        transport.simulateCorruptFrames(true)
        advanceTimeBy(500) // genau ein Tick bei 2 Hz
        transport.simulateCorruptFrames(false)
        advanceTimeBy(5_000)
        runCurrent()

        assertFalse(
            "Einzelner Fehler-Frame haette nicht sofort DEGRADED ausloesen duerfen, war $states",
            states.contains(ConnectionState.DEGRADED)
        )
        assertEquals(ConnectionState.STREAMING, supervisor.state.value)
    }

    @Test
    fun flatterndeVerbindungFuehrtNichtZuFailed() = runTest {
        // Gegentest zu nachErschoepftenVersuchenWirdFailedGemeldet: eine Verbindung, die immer
        // wieder kurz abbricht aber stets zurueckkommt, darf den Fehlschlagszaehler nie bis
        // maxAttempts auflaufen lassen.
        val transport = newTransport()
        val supervisor = newSupervisor(transport, maxAttempts = 8)
        val states = observeStates(supervisor)

        supervisor.start(device)
        runCurrent()
        assertEquals(ConnectionState.STREAMING, supervisor.state.value)

        repeat(10) {
            transport.simulateConnectionLoss()
            runCurrent()
            advanceTimeBy(1_300) // > erster Backoff-Schritt (max. 1,2s)
            runCurrent()
            assertEquals(ConnectionState.STREAMING, supervisor.state.value)
        }

        assertFalse("Flattern darf niemals FAILED ausloesen, war $states", states.contains(ConnectionState.FAILED))
    }
}
