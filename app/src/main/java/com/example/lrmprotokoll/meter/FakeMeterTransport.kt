package com.example.lrmprotokoll.meter

import java.time.Instant
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Simulator fuer [MeterTransport] (Plan Abschnitt 4.2). Liefert plausible Frames ohne
 * Hardware und laesst sich gezielt in die Fehlerzustaende versetzen, die Ausfallerkennung
 * und Alarmierung (M5) erkennen koennen muessen: harter Verbindungsabbruch, stiller
 * Datenstillstand bei bestehender Verbindung, und fehlerhafte Messwerte.
 */
class FakeMeterTransport(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
    private val frameRateHz: Double = 2.0,
    private val baseLevel: Double = 55.0,
) : MeterTransport {

    private val _state = MutableStateFlow(ConnectionState.IDLE)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _frames = MutableSharedFlow<MeterFrame>(extraBufferCapacity = 64)
    override val frames: SharedFlow<MeterFrame> = _frames.asSharedFlow()

    private val _lastFrameAt = MutableStateFlow<Instant?>(null)
    override val lastFrameAt: StateFlow<Instant?> = _lastFrameAt.asStateFlow()

    private val _frameQuality = MutableStateFlow(FrameQuality())
    override val frameQuality: StateFlow<FrameQuality> = _frameQuality.asStateFlow()

    private var emitJob: Job? = null
    private var stalled = false
    private var corruptFrames = false
    private var validFrameCount = 0L
    private var errorFrameCount = 0L
    private var throwOnConnect = false

    /**
     * Simuliert eine unerwartete Exception aus dem Transport, z.B. eine DeadObjectException
     * beim Neustart des Bluetooth-Stacks oder eine IllegalStateException aus getRemoteDevice()
     * bei ungueltig gewordener Adresse (Review-Befund 2, PR #16) - fuer den Test, dass
     * [ConnectionSupervisor] darauf wie auf einen normalen Fehlschlag reagiert statt die
     * Ueberwachung lautlos zu beenden.
     */
    fun simulateConnectException(enabled: Boolean) {
        throwOnConnect = enabled
    }

    override suspend fun connect(device: BoundDevice) {
        if (throwOnConnect) {
            throw IllegalStateException("Simulierter Verbindungsfehler (simulateConnectException)")
        }
        stopEmitting()
        // Wie beim realen Decoder (Pce323FrameDecoder.reset()) faengt jeder (Re-)Connect mit
        // sauberen Zaehlern an - sonst wuerde die Fehlerrate eines fruehen Verbindungsversuchs
        // ueber einen Reconnect hinweg fortleben.
        validFrameCount = 0
        errorFrameCount = 0
        _frameQuality.value = FrameQuality()
        _state.value = ConnectionState.CONNECTING
        _state.value = ConnectionState.DISCOVERING
        _state.value = ConnectionState.SUBSCRIBING
        startEmitting()
    }

    override suspend fun disconnect() {
        stopEmitting()
        _state.value = ConnectionState.DISCONNECTED
    }

    override suspend fun send(command: MeterCommand): Result<Unit> {
        if (_state.value != ConnectionState.STREAMING) {
            return Result.failure(IllegalStateException("FakeMeterTransport ist nicht verbunden"))
        }
        return Result.success(Unit)
    }

    /** Simuliert einen harten Abbruch, wie er ueber einen GATT-Disconnect-Callback eintrifft. */
    fun simulateConnectionLoss() {
        stopEmitting()
        _state.value = ConnectionState.DISCONNECTED
    }

    /**
     * Simuliert Datenstillstand bei weiterhin bestehender Verbindung (Plan Abschnitt 7.1):
     * der Zustand bleibt STREAMING, es fliessen aber keine Frames mehr - genau das Muster,
     * das ueber [lastFrameAt] als stiller Ausfall erkannt werden muss.
     */
    fun simulateStall(stalled: Boolean) {
        this.stalled = stalled
    }

    /**
     * Setzt den Verbindungszustand explizit (fuer Tests).
     */
    fun setState(state: ConnectionState) {
        _state.value = state
    }

    /**
     * Speist einen Frame deterministisch ein (fuer UI- und Regressionstests).
     */
    suspend fun emitFrame(frame: MeterFrame) {
        validFrameCount++
        _lastFrameAt.value = frame.receivedAt
        _frames.emit(frame)
        _frameQuality.value = FrameQuality(validFrameCount + errorFrameCount, errorFrameCount)
    }

    /**
     * Bequemer Helper zum Einspeisen eines Frames mit Standardwerten.
     */
    suspend fun emitFrame(
        level: Double = baseLevel,
        weighting: Weighting? = Weighting.A,
        timeWeighting: TimeWeighting? = TimeWeighting.FAST,
        range: MeasurementRange? = MeasurementRange.RANGE_30_130,
        modeAssumptionConfirmed: Boolean = true,
    ) {
        val nowMs = System.currentTimeMillis().coerceAtLeast(lastEmittedEpochMs + 1)
        lastEmittedEpochMs = nowMs
        val frame = MeterFrame(
            level = level,
            weighting = weighting,
            timeWeighting = timeWeighting,
            range = range,
            holdMax = null,
            holdMin = null,
            receivedAt = Instant.ofEpochMilli(nowMs),
            modeAssumptionConfirmed = modeAssumptionConfirmed,
        )
        emitFrame(frame)
    }

    /**
     * Laesst nachfolgende Ticks wie verworfene Decode-Kandidaten wirken: kein Frame wird
     * emittiert, nur der Fehlerzaehler steigt - genau das Verhalten des echten
     * [Pce323FrameDecoder] bei einem unplausiblen Wert (Pegel ausserhalb 20-140 dB), nicht ein
     * Frame MIT unplausiblem Pegel. Ein tatsaechlich emittiertes MeterFrame gilt sonst als
     * valides Wissen (siehe MeterFrame-Doc) - ein "Frame" mit erfundenem Pegel waere genau der
     * Vertrauensbruch, den diese Klasse an anderer Stelle bewusst vermeidet.
     */
    fun simulateCorruptFrames(corrupt: Boolean) {
        this.corruptFrames = corrupt
    }

    private fun startEmitting() {
        emitJob = scope.launch {
            _state.value = ConnectionState.STREAMING
            val periodMs = (1000.0 / frameRateHz).toLong()
            while (isActive) {
                if (!stalled) {
                    if (corruptFrames) {
                        errorFrameCount++
                    } else {
                        val frame = nextFrame()
                        validFrameCount++
                        _lastFrameAt.value = frame.receivedAt
                        _frames.emit(frame)
                    }
                    _frameQuality.value = FrameQuality(validFrameCount + errorFrameCount, errorFrameCount)
                }
                delay(periodMs)
            }
        }
    }

    private fun stopEmitting() {
        emitJob?.cancel()
        emitJob = null
    }

    private var lastEmittedEpochMs: Long = 0L

    private fun nextFrame(): MeterFrame {
        val nowMs = System.currentTimeMillis().coerceAtLeast(lastEmittedEpochMs + 1)
        lastEmittedEpochMs = nowMs
        val level = baseLevel + Random.nextDouble(-3.0, 3.0)
        // Spiegelt das reale BleMeterTransport-Verhalten: Bewertung, Zeitbewertung, Bereich
        // und Hold-Status sind beim echten Geraet unbekannt (siehe MeterFrame-Doc), der Fake
        // taeuscht hier bewusst kein Wissen vor, das es in Wirklichkeit nicht gibt.
        return MeterFrame(
            level = level,
            weighting = null,
            timeWeighting = null,
            range = null,
            holdMax = null,
            holdMin = null,
            receivedAt = Instant.ofEpochMilli(nowMs),
        )
    }
}
