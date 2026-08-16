package com.example.lrmprotokoll.meter

import java.time.Instant
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Schnittstelle zwischen App und Schallpegelmessgeraet (Plan Abschnitt 4.3). Die BLE-
 * Implementierung folgt in M2, sobald die GATT-UUIDs am realen PCE-323 ermittelt sind (M0).
 */
interface MeterTransport {
    val state: StateFlow<ConnectionState>
    val frames: SharedFlow<MeterFrame>
    val lastFrameAt: StateFlow<Instant?>

    suspend fun connect(device: BoundDevice)
    suspend fun disconnect()
    suspend fun send(command: MeterCommand): Result<Unit>
}

data class MeterFrame(
    val level: Double,
    val weighting: Weighting,
    val timeWeighting: TimeWeighting,
    val range: MeasurementRange,
    val holdMax: Boolean,
    val holdMin: Boolean,
    val receivedAt: Instant,
    val largeJump: Boolean = false,
)

enum class Weighting { A, C }

enum class TimeWeighting { FAST, SLOW }

enum class MeasurementRange {
    RANGE_30_130,
    RANGE_30_80,
    RANGE_50_100,
    RANGE_80_130,
}
