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

    private var emitJob: Job? = null
    private var stalled = false
    private var corruptFrames = false

    override suspend fun connect(device: BoundDevice) {
        stopEmitting()
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

    /** Laesst nachfolgende Frames unplausible Pegelwerte (ausserhalb 20-140 dB) tragen. */
    fun simulateCorruptFrames(corrupt: Boolean) {
        this.corruptFrames = corrupt
    }

    private fun startEmitting() {
        emitJob = scope.launch {
            _state.value = ConnectionState.STREAMING
            val periodMs = (1000.0 / frameRateHz).toLong()
            while (isActive) {
                if (!stalled) {
                    val frame = nextFrame()
                    _lastFrameAt.value = frame.receivedAt
                    _frames.emit(frame)
                }
                delay(periodMs)
            }
        }
    }

    private fun stopEmitting() {
        emitJob?.cancel()
        emitJob = null
    }

    private fun nextFrame(): MeterFrame {
        val level = if (corruptFrames) {
            listOf(-10.0, 999.0).random()
        } else {
            baseLevel + Random.nextDouble(-3.0, 3.0)
        }
        return MeterFrame(
            level = level,
            weighting = Weighting.A,
            timeWeighting = TimeWeighting.FAST,
            range = MeasurementRange.RANGE_30_130,
            holdMax = false,
            holdMin = false,
            receivedAt = Instant.now(),
        )
    }
}
