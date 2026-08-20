package com.example.lrmprotokoll.meter

import com.example.lrmprotokoll.data.DiagnosticLogDao
import com.example.lrmprotokoll.data.DiagnosticLogEntity
import com.example.lrmprotokoll.diagnose.DiagnosticLogger
import java.time.Duration
import java.time.Instant
import kotlin.random.Random
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PROMPT_M3 Aufgabe 1+2: Tests fuer den Verbindungs-Zustandsautomaten. Jeder Testfall aus dem
 * Prompt ist einzeln abgedeckt; wo sinnvoll bilden zwei Tests bewusst ein Gegentest-Paar, das
 * beide Seiten einer Schwelle prueft (z.B. hoheFehlerrateFuehrtZuDegraded vs.
 * einzelnerFehlerFrameLoestKeinSofortigesDegradedAus fuer die Fehlerraten-Schwelle,
 * nachErschoepftenVersuchenWirdFailedGemeldet vs. flatterndeVerbindungFuehrtNichtZuFailed fuer
 * den Fehlschlagszaehler, flatterndeVerbindungFuehrtNichtZuFailed [stabile Sessions] vs.
 * kurzesFlatternEskaliertDurchBackoffStattEndlosSofortigerReconnects [Sessions unter
 * minStableSession] fuer die Reset-Schwelle) - so faellt der jeweils andere Test durch, wenn
 * eine der beiden Seiten der Logik entfernt oder falsch verschoben wird.
 *
 * Ergaenzt um drei Regressionstests aus dem Review von PR #16:
 * exceptionBeimVerbindenBeendetUeberwachungNichtLautlos (Befund 2), sowie je eine zusaetzliche
 * Pruefung in stallFuehrtNachTStaleZuDegradedUndErholtSichNachEndeDesStalls und
 * hoheFehlerrateFuehrtZuDegraded, dass kein spontanes DISCONNECTED zwischen DEGRADED und
 * RECONNECTING auftaucht (Befund 1).
 *
 * Alle Zeiten laufen ueber die virtuelle Zeit von [runTest]: sowohl [ConnectionSupervisor] als
 * auch [FakeMeterTransport] werden mit [TestScope.backgroundScope] konstruiert, die injizierte
 * [InstantSource] liest [TestScope.testScheduler], damit das eigene Fehlerraten-Zeitfenster
 * synchron zur selben virtuellen Uhr laeuft. WICHTIG: sobald ein Transport aktiv Frames sendet
 * oder wiederholt neu verbindet, plant er sich per delay() immer weiter neu ein -
 * [kotlinx.coroutines.test.advanceUntilIdle] wuerde dann nie "idle" werden. Deshalb wird hier
 * durchgehend [advanceTimeBy] mit konkreten, aus dem erwarteten Verhalten hergeleiteten
 * Obergrenzen verwendet, gefolgt von [runCurrent] fuer alles genau an der Zeitgrenze.
 *
 * Robolectric statt reinem JUnit, weil exceptionBeimVerbindenBeendetUeberwachungNichtLautlos
 * den echten android.util.Log-Aufruf in ConnectionSupervisor durchlaeuft - ohne Shadow wirft
 * das in einem reinen JVM-Unit-Test "Method w in android.util.Log not mocked".
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectionSupervisorTest {

    private val device = BoundDevice(address = "AA:BB:CC:DD:EE:FF", name = "PCE-323")

    private fun TestScope.newTransport(frameRateHz: Double = 2.0): FakeMeterTransport =
        FakeMeterTransport(scope = backgroundScope, frameRateHz = frameRateHz)

    private class FakeDiagnosticLogDao : DiagnosticLogDao {
        val zeilen = mutableListOf<DiagnosticLogEntity>()
        override suspend fun insert(eintrag: DiagnosticLogEntity) { zeilen += eintrag }
        override fun alle() = flowOf(zeilen.sortedByDescending { it.timestamp })
        override suspend fun loescheAelterAls(grenze: Long) { zeilen.removeAll { it.timestamp < grenze } }
    }

    private fun TestScope.newSupervisor(
        transport: MeterTransport,
        adapterEnabled: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow(),
        staleAfter: Duration = Duration.ofSeconds(5),
        errorRateWindow: Duration = Duration.ofSeconds(30),
        maxAttempts: Int = 8,
        minStableSession: Duration = Duration.ofSeconds(5),
        random: Random = Random(1),
        expectedFramePeriod: Duration? = null,
        diagnosticLogger: DiagnosticLogger? = null,
    ): ConnectionSupervisor {
        val clock = InstantSource { Instant.EPOCH.plusMillis(testScheduler.currentTime) }
        return ConnectionSupervisor(
            transport = transport,
            scope = backgroundScope,
            adapterEnabled = adapterEnabled,
            now = clock,
            random = random,
            staleAfter = staleAfter,
            errorRateWindow = errorRateWindow,
            maxAttempts = maxAttempts,
            minStableSession = minStableSession,
            expectedFramePeriod = expectedFramePeriod,
            diagnosticLogger = diagnosticLogger,
        )
    }

    /** Liefert immer 0 Jitter, damit Backoff-Werte in Tests exakt den Nominalwerten entsprechen. */
    private val zeroJitterRandom = object : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextDouble(from: Double, until: Double): Double = 0.0
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

    /**
     * Regressionspruefung zu Review-Befund 1 (PR #16): ohne den supervisorOverride-Mechanismus
     * wuerde das eigene transport.disconnect() nach dem Setzen von DEGRADED den Wert binnen
     * kuerzester Zeit ueber den forwarder auf DISCONNECTED ueberschreiben, bevor RECONNECTING
     * folgt - genau dieses spontane DISCONNECTED darf zwischen DEGRADED und RECONNECTING nicht
     * mehr auftauchen.
     */
    private fun assertNoSpuriousDisconnectAroundDegraded(states: List<ConnectionState>) {
        val degradedIndex = states.indexOf(ConnectionState.DEGRADED)
        assertTrue("DEGRADED sollte im Zustandsverlauf vorkommen, war $states", degradedIndex >= 0)
        val afterDegraded = states.subList(degradedIndex + 1, states.size)
        val reconnectingOffset = afterDegraded.indexOf(ConnectionState.RECONNECTING)
        assertTrue("RECONNECTING sollte nach DEGRADED folgen, war $states", reconnectingOffset >= 0)
        val between = afterDegraded.subList(0, reconnectingOffset)
        assertFalse(
            "DEGRADED wurde durch ein zwischenzeitliches DISCONNECTED vom forwarder " +
                "ueberschrieben (Review-Befund 1), Zustaende zwischen DEGRADED und " +
                "RECONNECTING: $between",
            between.contains(ConnectionState.DISCONNECTED)
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
        assertNoSpuriousDisconnectAroundDegraded(states)

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
        assertNoSpuriousDisconnectAroundDegraded(states)
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
        // Gegentest zu nachErschoepftenVersuchenWirdFailedGemeldet: eine Verbindung, die
        // WIEDERHOLT STABIL streamt (mindestens minStableSession) und danach abbricht, darf den
        // Fehlschlagszaehler nie bis maxAttempts auflaufen lassen. Sessions UNTER der Schwelle
        // sind ein separater Fall, siehe kurzesFlatternEskaliertDurchBackoffStattEndlosSofortigerReconnects
        // (Review-Befund 3, PR #16) - ohne diese Unterscheidung wuerde dieser Test schon durch
        // reinen Zufall gruen bleiben, obwohl der Backoff nie greift.
        val transport = newTransport()
        val minStableSession = Duration.ofSeconds(5)
        val supervisor = newSupervisor(transport, maxAttempts = 8, minStableSession = minStableSession)
        val states = observeStates(supervisor)

        supervisor.start(device)
        runCurrent()
        assertEquals(ConnectionState.STREAMING, supervisor.state.value)

        repeat(10) {
            advanceTimeBy(minStableSession.toMillis() + 500) // stabile Session, dann erst abbrechen
            runCurrent()
            transport.simulateConnectionLoss()
            runCurrent()
            advanceTimeBy(1_300) // > erster Backoff-Schritt (max. 1,2s) - stets Stufe 1 dank Reset
            runCurrent()
            assertEquals(ConnectionState.STREAMING, supervisor.state.value)
        }

        assertFalse("Flattern darf niemals FAILED ausloesen, war $states", states.contains(ConnectionState.FAILED))
    }

    @Test
    fun kurzesFlatternEskaliertDurchBackoffStattEndlosSofortigerReconnects() = runTest {
        // Regressionstest zu Review-Befund 3 (PR #16): eine Session UNTER minStableSession darf
        // consecutiveFailures NICHT zuruecksetzen - sonst wuerde ein Geraet, das verbindet, kurz
        // Daten liefert und sofort wieder abbricht, endlos im Sekundentakt neu verbunden (der
        // Backoff greift nie, weil jeder Zyklus formal als Erfolg zaehlte). Genau dieses
        // Dauerzyklus-Muster ist es, gegen das Aufgabe 5 in diesem PR (gatt.close() bei
        // spontanem Disconnect) die status-133-Kaskade nach Plan 5.3 vermeiden soll.
        //
        // Null-Jitter macht die Luecken deterministisch exakt pruefbar statt nur "groesser
        // werdend" - ohne die Mindestdauer-Pruefung blieben alle Luecken bei genau 1s.
        val transport = newTransport()
        val supervisor = newSupervisor(
            transport,
            minStableSession = Duration.ofSeconds(5),
            random = zeroJitterRandom,
        )
        val reconnectingAtMillis = mutableListOf<Long>()
        backgroundScope.launch {
            supervisor.state.collect {
                if (it == ConnectionState.RECONNECTING) reconnectingAtMillis.add(testScheduler.currentTime)
            }
        }

        supervisor.start(device)
        runCurrent()
        assertEquals(ConnectionState.STREAMING, supervisor.state.value)

        // Jede Session bricht sofort wieder ab - weit unter minStableSession.
        val nominalBackoffsMs = longArrayOf(1_000, 2_000, 4_000)
        nominalBackoffsMs.forEach { backoff ->
            transport.simulateConnectionLoss()
            runCurrent()
            advanceTimeBy(backoff)
            runCurrent()
        }

        assertEquals(3, reconnectingAtMillis.size)
        val gaps = reconnectingAtMillis.zipWithNext { a, b -> b - a }
        assertEquals(listOf(1_000L, 2_000L), gaps)
    }

    @Test
    fun exceptionBeimVerbindenBeendetUeberwachungNichtLautlos() = runTest {
        // Regressionstest zu Review-Befund 2 (PR #16): eine Exception aus transport.connect()
        // (z.B. DeadObjectException beim Neustart des Bluetooth-Stacks) darf die Ueberwachung
        // nicht lautlos beenden - sie muss wie ein normaler Fehlschlag behandelt werden: Backoff,
        // weiterer Versuch, nach maxAttempts sauber FAILED statt eingefroren auf dem letzten Wert.
        val transport = newTransport()
        transport.simulateConnectException(true)
        val supervisor = newSupervisor(transport, staleAfter = Duration.ofMillis(1), maxAttempts = 3)

        supervisor.start(device)
        runCurrent()
        advanceTimeBy(5_000) // deckt die 2 Luecken (1s, 2s) mit max. +20% Jitter ab
        runCurrent()

        assertEquals(ConnectionState.FAILED, supervisor.state.value)
    }

    @Test
    fun kadenzWeitAusserhalbDerErwartungFuehrtZuDegraded() = runTest {
        // Plan Abschnitt 6, Stream-Plausibilisierung: 10 Hz (100ms Periode) liegt weit ausserhalb
        // von 515ms +-20% (412-618ms) - genau das Muster, das ein untergeschobenes Geraet mit
        // abweichender Framerate erzeugen wuerde.
        val transport = newTransport(frameRateHz = 10.0)
        val supervisor = newSupervisor(transport, expectedFramePeriod = Duration.ofMillis(515))
        val states = observeStates(supervisor)

        supervisor.start(device)
        runCurrent()
        assertEquals(ConnectionState.STREAMING, supervisor.state.value)

        advanceTimeBy(300) // deckt mindestens zwei aufeinanderfolgende 100ms-Deltas ab
        runCurrent()

        assertTrue(
            "Stark abweichende Kadenz haette DEGRADED ausloesen muessen, war $states",
            states.contains(ConnectionState.DEGRADED)
        )
        assertNoSpuriousDisconnectAroundDegraded(states)
    }

    @Test
    fun kadenzInnerhalbDerToleranzLoestKeinDegradedAus() = runTest {
        // Gegentest zu kadenzWeitAusserhalbDerErwartungFuehrtZuDegraded: die nominale 2-Hz-Rate
        // des Fakes (500ms) liegt innerhalb von 515ms +-20% (412-618ms) und darf die Verbindung
        // nicht trennen - sonst wuerde die Pruefung bereits das reale Geraet bei normalem Jitter
        // aussperren (M0-Aufzeichnung: 449-586ms, Mittel ~515ms).
        val transport = newTransport()
        val supervisor = newSupervisor(transport, expectedFramePeriod = Duration.ofMillis(515))
        val states = observeStates(supervisor)

        supervisor.start(device)
        runCurrent()
        assertEquals(ConnectionState.STREAMING, supervisor.state.value)

        advanceTimeBy(5_000)
        runCurrent()

        assertFalse(
            "Kadenz innerhalb der Toleranz haette kein DEGRADED ausloesen duerfen, war $states",
            states.contains(ConnectionState.DEGRADED)
        )
        assertEquals(ConnectionState.STREAMING, supervisor.state.value)
    }

    /**
     * Owner-Auftrag: "mehr Debuginformationen ... bzgl der Bluetooth Verbindungs Robustheit".
     * Bislang protokollierte [com.example.lrmprotokoll.diagnose.DiagnosticLogger] nur DEGRADED-
     * Ursachen und Verbindungsversuche, die mit einer Exception scheiterten - der komplette
     * restliche Lebenszyklus (erfolgreicher Verbindungsaufbau, Reconnect-Versuche mit Backoff,
     * endgueltiger Fehlschlag, Wiederherstellung, Adapter-Pause) blieb unsichtbar, obwohl
     * [ConnectionSupervisor] all das bereits selbst weiss.
     */
    @Test
    fun erfolgreicherVerbindungsaufbauWirdProtokolliert() = runTest {
        val transport = newTransport()
        val dao = FakeDiagnosticLogDao()
        val logger = DiagnosticLogger(dao, InstantSource { Instant.EPOCH.plusMillis(testScheduler.currentTime) }, aktiv = { true })
        val supervisor = newSupervisor(transport, diagnosticLogger = logger)

        supervisor.start(device)
        runCurrent()

        assertTrue(
            "Erwartete einen Log-Eintrag zum erfolgreichen Verbindungsaufbau, war ${dao.zeilen.map { it.message }}",
            dao.zeilen.any { it.message.contains("Verbunden, Streaming gestartet") },
        )
    }

    @Test
    fun reconnectVersucheMitBackoffWerdenProtokolliert() = runTest {
        val transport = NeverStreamingTransport(testScheduler)
        val dao = FakeDiagnosticLogDao()
        val logger = DiagnosticLogger(dao, InstantSource { Instant.EPOCH.plusMillis(testScheduler.currentTime) }, aktiv = { true })
        val supervisor = newSupervisor(
            transport, staleAfter = Duration.ofMillis(1), random = zeroJitterRandom, diagnosticLogger = logger,
        )

        supervisor.start(device)
        runCurrent()
        advanceTimeBy(1_100) // deckt den ersten Backoff-Schritt (1s) ab
        runCurrent()

        assertTrue(
            "Erwartete einen Reconnect-Log-Eintrag mit Backoff-Angabe, war ${dao.zeilen.map { it.message }}",
            dao.zeilen.any { it.message.contains("Reconnect-Versuch 1") && it.message.contains("Backoff") },
        )
    }

    @Test
    fun endgueltigerFehlschlagWirdProtokolliert() = runTest {
        val transport = NeverStreamingTransport(testScheduler)
        val dao = FakeDiagnosticLogDao()
        val logger = DiagnosticLogger(dao, InstantSource { Instant.EPOCH.plusMillis(testScheduler.currentTime) }, aktiv = { true })
        val supervisor = newSupervisor(
            transport, staleAfter = Duration.ofMillis(1), maxAttempts = 3,
            random = zeroJitterRandom, diagnosticLogger = logger,
        )

        supervisor.start(device)
        runCurrent()
        advanceTimeBy(5_000) // deckt die 2 Luecken (1s, 2s) ab, siehe nachErschoepftenVersuchenWirdFailedGemeldet
        runCurrent()

        assertEquals(ConnectionState.FAILED, supervisor.state.value)
        assertTrue(
            "Erwartete einen Log-Eintrag zum endgueltigen Fehlschlag, war ${dao.zeilen.map { it.message }}",
            dao.zeilen.any { it.message.contains("endgueltig fehlgeschlagen nach 3 Versuchen") },
        )
    }

    @Test
    fun wiederherstellungNachFehlversuchWirdProtokolliert() = runTest {
        // simulateConnectException(true) laesst throwOnConnect gesetzt, bis es wieder auf false
        // geschaltet wird (siehe FakeMeterTransport) - hier bewusst nur fuer den ersten Versuch
        // aktiv, um eine kurze Funkstoerung zu simulieren, die sich von selbst erholt.
        val transport = newTransport()
        transport.simulateConnectException(true)
        val dao = FakeDiagnosticLogDao()
        val logger = DiagnosticLogger(dao, InstantSource { Instant.EPOCH.plusMillis(testScheduler.currentTime) }, aktiv = { true })
        val supervisor = newSupervisor(transport, random = zeroJitterRandom, diagnosticLogger = logger)

        supervisor.start(device)
        runCurrent() // erster Versuch scheitert an der Exception
        transport.simulateConnectException(false) // erholt sich vor dem naechsten Versuch
        advanceTimeBy(1_100) // deckt den ersten Backoff-Schritt (1s) vor dem erfolgreichen Versuch ab
        runCurrent()

        assertEquals(ConnectionState.STREAMING, supervisor.state.value)
        assertTrue(
            "Erwartete einen Log-Eintrag zur Wiederherstellung, war ${dao.zeilen.map { it.message }}",
            dao.zeilen.any { it.message.contains("wiederhergestellt") },
        )
    }

    @Test
    fun selbstAusgeloesterDisconnectWirdNichtZusaetzlichAlsFremdAbbruchProtokolliert() = runTest {
        // Ohne die done.complete()-Absicherung in disconnectWatcher wuerde ein DEGRADED-
        // ausgeloester transport.disconnect() (hier: Stall) zusaetzlich zur spezifischen
        // "DEGRADED: Datenstillstand"-Zeile faelschlich auch eine generische "vom Geraet/System
        // beendet"-Zeile erzeugen, obwohl die App selbst getrennt hat.
        val transport = newTransport()
        val dao = FakeDiagnosticLogDao()
        val logger = DiagnosticLogger(dao, InstantSource { Instant.EPOCH.plusMillis(testScheduler.currentTime) }, aktiv = { true })
        val supervisor = newSupervisor(transport, staleAfter = Duration.ofSeconds(5), diagnosticLogger = logger)

        supervisor.start(device)
        runCurrent()
        transport.simulateStall(true)
        advanceTimeBy(5_100)
        runCurrent()

        assertTrue(
            "Erwartete die spezifische DEGRADED-Ursache, war ${dao.zeilen.map { it.message }}",
            dao.zeilen.any { it.message.contains("DEGRADED: Datenstillstand") },
        )
        assertFalse(
            "Ein selbst ausgeloester Abbruch darf nicht zusaetzlich als Fremdabbruch erscheinen, war ${dao.zeilen.map { it.message }}",
            dao.zeilen.any { it.message.contains("vom Geraet/System beendet") },
        )
    }
}
