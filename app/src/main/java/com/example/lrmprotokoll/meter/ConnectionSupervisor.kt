package com.example.lrmprotokoll.meter

import java.time.Duration
import java.time.Instant
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Zeitquelle als Funktionstyp statt direktem `Instant.now()` (PROMPT_M3 Aufgabe 1) - sonst
 * sind die Backoff-/Staleness-Tests nicht deterministisch testbar und dauern in echter Zeit. */
fun interface InstantSource {
    fun now(): Instant

    companion object {
        val System = InstantSource { Instant.now() }
    }
}

private val BACKOFF_STEPS_SECONDS = longArrayOf(1, 2, 4, 8, 16, 30)
private const val BACKOFF_CONSTANT_SECONDS = 60L
private const val BACKOFF_JITTER_FRACTION = 0.2
private const val MIN_SAMPLES_FOR_ERROR_RATE = 5

/**
 * Treibt den Verbindungs-Zustandsautomaten aus Plan Abschnitt 5.1 ueber die reine
 * [MeterTransport]-Schnittstelle - kennt NICHT [com.example.lrmprotokoll.meter.ble.BleMeterTransport],
 * nur so bleibt sie vollstaendig gegen [FakeMeterTransport] testbar (PROMPT_M3 Aufgabe 1).
 *
 * Vier unabhaengige Ausfallsignale (Plan Abschnitt 7.1) fuehren zu proaktivem Reconnect:
 * GATT-Disconnect (direkt von [transport]), Staleness (kein Frame seit [staleAfter]),
 * Bluetooth-Adapter aus ([adapterEnabled]) und Fehlerrate ueber [errorRateWindow] > [errorRateThreshold].
 *
 * [maxAttempts] = 8: durchlaeuft die komplette Backoff-Folge (1,2,4,8,16,30 s) einmal vollstaendig
 * plus zwei weitere Versuche im konstanten 60-s-Takt, bevor [ConnectionState.FAILED] gemeldet wird -
 * in Summe rund zwei Minuten Wartezeit ueber acht Versuche. Kein Wert aus Plan/Prompt vorgegeben,
 * eigene Abwaegung (nicht einer der sieben in Plan Abschnitt 13 als offen markierten Punkte).
 * Ein Reconnect NACH mindestens einem erfolgreich gestreamten Frame setzt den Zaehler zurueck -
 * eine flatternde, aber grundsaetzlich erreichbare Verbindung darf nie FAILED auslösen.
 */
class ConnectionSupervisor(
    private val transport: MeterTransport,
    private val scope: CoroutineScope,
    private val adapterEnabled: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow(),
    private val now: InstantSource = InstantSource.System,
    private val random: Random = Random.Default,
    private val staleAfter: Duration = Duration.ofSeconds(5),
    private val errorRateWindow: Duration = Duration.ofSeconds(30),
    private val errorRateThreshold: Double = 0.2,
    private val maxAttempts: Int = 8,
) {
    private val _state = MutableStateFlow(ConnectionState.IDLE)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private var job: Job? = null
    private var currentDevice: BoundDevice? = null

    /**
     * (Re-)Startet die Ueberwachung fuer [device]. Ein Aufruf fuer das bereits aktiv
     * ueberwachte Geraet ist ein No-Op - sowohl [com.example.lrmprotokoll.audio.AudioRecordingService]
     * (beim eigenen Start) als auch die UI (beim Koppeln) rufen [start] auf, ohne sich
     * abzustimmen; ohne diese Absicherung wuerde der zweite Aufruf eine laufende Verbindung
     * unnoetig neu aufbauen.
     */
    fun start(device: BoundDevice) {
        if (job?.isActive == true && currentDevice == device) return
        currentDevice = device
        job?.cancel()
        job = scope.launch { supervise(device) }
    }

    /** Beendet die Ueberwachung und trennt die Verbindung. */
    fun stop() {
        job?.cancel()
        job = null
        currentDevice = null
        scope.launch { runCatching { transport.disconnect() } }
        _state.value = ConnectionState.IDLE
    }

    private suspend fun supervise(device: BoundDevice): Unit = coroutineScope {
        // Spiegelt die feingranularen Zwischenzustaende des Transports (CONNECTING,
        // DISCOVERING, SUBSCRIBING, STREAMING, DISCONNECTED, FAILED) direkt in [_state] -
        // die RECONNECTING/DEGRADED-Ueberschreibungen unten bleiben bestehen, solange sich
        // transport.state selbst nicht aendert (StateFlow emittiert nur bei echter Aenderung).
        val forwarder = launch { transport.state.collectLatest { _state.value = it } }
        try {
            var consecutiveFailures = 0
            var isFirstAttempt = true
            while (isActive) {
                if (!adapterEnabled.value) {
                    _state.value = ConnectionState.DISCONNECTED
                    adapterEnabled.first { it } // pausiert, bis der Adapter wieder an ist
                }

                if (!isFirstAttempt) {
                    _state.value = ConnectionState.RECONNECTING
                    delay(backoffDelayMillis(consecutiveFailures))
                    if (!adapterEnabled.value) continue // waehrend der Wartezeit wieder ausgeschaltet
                }
                isFirstAttempt = false

                when (attemptOnce(device)) {
                    AttemptOutcome.STREAMED_THEN_LOST -> consecutiveFailures = 0
                    AttemptOutcome.ADAPTER_OFF -> {
                        consecutiveFailures = 0
                        isFirstAttempt = true // sofortige Wiederaufnahme ohne Backoff-Wartezeit
                    }
                    AttemptOutcome.NEVER_STREAMED -> {
                        consecutiveFailures++
                        if (consecutiveFailures >= maxAttempts) {
                            _state.value = ConnectionState.FAILED
                            return@coroutineScope
                        }
                    }
                }
            }
        } finally {
            forwarder.cancel()
        }
    }

    private enum class AttemptOutcome { STREAMED_THEN_LOST, NEVER_STREAMED, ADAPTER_OFF }

    private suspend fun attemptOnce(device: BoundDevice): AttemptOutcome {
        transport.connect(device)
        // transport.connect() kehrt erst zurueck, wenn der GATT-Aufbau (inkl. eigener Timeouts
        // in GattQueue/BleMeterTransport) abgeschlossen ist - hier faengt nur noch das Warten
        // auf das ERSTE Frame nach erfolgreichem CCCD-Write an. Eine stehende Verbindung ohne
        // Datenfluss zaehlt als Ausfall, nicht als Erfolg (Plan Abschnitt 5.1).
        val reached = withTimeoutOrNull(staleAfter.toMillis()) {
            transport.state.first {
                it == ConnectionState.STREAMING || it == ConnectionState.FAILED || it == ConnectionState.DISCONNECTED
            }
        }
        if (reached != ConnectionState.STREAMING) {
            transport.disconnect()
            return AttemptOutcome.NEVER_STREAMED
        }
        return monitorStreamingSession()
    }

    private suspend fun monitorStreamingSession(): AttemptOutcome = coroutineScope {
        val done = CompletableDeferred<AttemptOutcome>()

        val disconnectWatcher = launch {
            transport.state.first { it == ConnectionState.DISCONNECTED || it == ConnectionState.FAILED }
            done.complete(AttemptOutcome.STREAMED_THEN_LOST)
        }

        // Watchdog-Timer: jedes neue lastFrameAt bricht den laufenden delay() ab und startet ihn
        // neu (collectLatest). Laeuft er ab, ist seit staleAfter kein Frame mehr angekommen.
        val stalenessWatcher = launch {
            transport.lastFrameAt.collectLatest { last ->
                if (last == null) return@collectLatest
                delay(staleAfter.toMillis())
                _state.value = ConnectionState.DEGRADED
                transport.disconnect()
                done.complete(AttemptOutcome.STREAMED_THEN_LOST)
            }
        }

        // Gleitendes Fenster statt absoluter Zaehler (PROMPT_M3-Warnung): totalFrames/errorFrames
        // in transport.frameQuality werden bei jedem Reconnect zurueckgesetzt. Ein absoluter
        // Vergleich wuerde direkt nach jedem Reconnect auf winzigen Stichproben eine instabile
        // Rate liefern und ggf. sofort den naechsten Reconnect ausloesen - deshalb hier ein
        // eigenes, zeitbasiertes Fenster ueber [now], das nur Deltas innerhalb der letzten
        // [errorRateWindow] betrachtet.
        val errorRateWatcher = launch {
            val window = ArrayDeque<Pair<Instant, FrameQuality>>()
            transport.frameQuality.collect { quality ->
                val t = now.now()
                window.addLast(t to quality)
                while (window.size > 1 && Duration.between(window.first().first, t) > errorRateWindow) {
                    window.removeFirst()
                }
                val (_, baseline) = window.first()
                val totalDelta = quality.totalFrames - baseline.totalFrames
                val errorDelta = quality.errorFrames - baseline.errorFrames
                if (totalDelta >= MIN_SAMPLES_FOR_ERROR_RATE) {
                    val rate = errorDelta.toDouble() / totalDelta
                    if (rate > errorRateThreshold) {
                        _state.value = ConnectionState.DEGRADED
                        transport.disconnect()
                        done.complete(AttemptOutcome.STREAMED_THEN_LOST)
                    }
                }
            }
        }

        val adapterWatcher = launch {
            adapterEnabled.first { !it }
            transport.disconnect()
            done.complete(AttemptOutcome.ADAPTER_OFF)
        }

        val result = done.await()
        disconnectWatcher.cancel()
        stalenessWatcher.cancel()
        errorRateWatcher.cancel()
        adapterWatcher.cancel()
        result
    }

    private fun backoffDelayMillis(consecutiveFailures: Int): Long {
        require(consecutiveFailures >= 0) { "consecutiveFailures kann nicht negativ sein" }
        // consecutiveFailures == 0 tritt nach einem STREAMED_THEN_LOST-Reset auf (Geraet war
        // gerade noch erreichbar) - das bekommt bewusst denselben ersten Backoff-Schritt wie
        // der allererste echte Fehlschlag, statt sofort ohne Wartezeit erneut zu verbinden:
        // sonst wuerde schnelles Flattern (Plan 7.1, PROMPT_M3-Testfall) zu einem Reconnect-
        // Sturm ohne jede Verzoegerung fuehren.
        val stepIndex = (consecutiveFailures - 1).coerceAtLeast(0)
        val baseSeconds = BACKOFF_STEPS_SECONDS.getOrElse(stepIndex) { BACKOFF_CONSTANT_SECONDS }
        val jitter = 1.0 + random.nextDouble(-BACKOFF_JITTER_FRACTION, BACKOFF_JITTER_FRACTION)
        return (baseSeconds * 1000 * jitter).toLong().coerceAtLeast(0)
    }
}
