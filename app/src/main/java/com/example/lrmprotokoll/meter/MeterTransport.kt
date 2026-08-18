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

/**
 * Ein dekodierter Messwert. [weighting], [timeWeighting], [range], [holdMax] und [holdMin]
 * sind nullable statt mit einem Default belegt: `null` heisst, dass fuer das jeweilige Feld
 * ueberhaupt keine Byte-Position bekannt ist - ein erfundener oder defaulteter Wert wuerde hier
 * Wissen vortaeuschen, das nicht existiert. Deshalb bildet `null` "unbekannt" ab, nicht ein
 * Default oder ein synthetisches UNKNOWN-Enum-Element: jede Verwendungsstelle wird durch den
 * Compiler gezwungen, den Fall explizit zu behandeln, statt ihn versehentlich wie einen echten
 * Wert zu behandeln.
 *
 * Fuer [weighting], [timeWeighting] und [range] gilt zusaetzlich [modeAssumptionConfirmed]:
 * Die Byte-Position dieser drei Felder ist durch isolierte Geraetetests belegt
 * (docs/PROTOKOLL_PCE-323.md, Abschnitt 9), welcher Bytewert aber welchem Anzeigezustand
 * entspricht, ist noch eine unbestaetigte Annahme. Solange [modeAssumptionConfirmed] `false`
 * ist, bedeutet ein non-null-Wert also "angenommen", NICHT "bestaetigt" - insbesondere darf
 * [weighting] NICHT als sicheres A/dBA behandelt werden. Jede Stelle, die diese drei Felder als
 * Tatsache persistiert oder anzeigt (statt nur zum Geraete-Abgleich zu spiegeln), MUSS
 * [modeAssumptionConfirmed] pruefen.
 */
data class MeterFrame(
    val level: Double,
    val weighting: Weighting?,
    val timeWeighting: TimeWeighting?,
    val range: MeasurementRange?,
    val holdMax: Boolean?,
    val holdMin: Boolean?,
    val receivedAt: Instant,
    val largeJump: Boolean = false,
    val modeAssumptionConfirmed: Boolean = false,
)

enum class Weighting { A, C }

enum class TimeWeighting { FAST, SLOW }

enum class MeasurementRange {
    RANGE_30_130,
    RANGE_30_80,
    RANGE_50_100,
    RANGE_80_130,
}
