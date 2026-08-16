package com.example.lrmprotokoll.meter

import java.time.Instant
import kotlin.math.abs

private const val START_MARKER: Byte = 0x7F.toByte()
private const val END_MARKER: Byte = 0x00.toByte()
private const val FRAME_SIZE = 6
private const val MIN_PLAUSIBLE_LEVEL = 20.0
private const val MAX_PLAUSIBLE_LEVEL = 140.0
private const val LARGE_JUMP_THRESHOLD_DB = 40.0

/**
 * Dekodiert den 6-Byte-Frame-Strom des PCE-323 (Plan Abschnitt 2.2):
 *
 *   [0] 0x7F Startmarker
 *   [1..2] Messwert, 16 Bit big endian -> dB = wert / 10.0
 *   [3] bit0: 0=A-Bewertung 1=C-Bewertung   bit1: 0=Fast 1=Slow
 *   [4] bit0..1: Messbereich 0=30-130 1=30-80 2=50-100 3=80-130   bit2: Max-Hold   bit3: Min-Hold
 *   [5] 0x00 Endmarker
 *
 * Arbeitet byteweise ueber einen Ringpuffer statt paketweise, weil BLE-Notifications
 * fragmentiert eintreffen koennen: ein Frame kann sich ueber mehrere [feed]-Aufrufe erstrecken,
 * und mehrere Frames koennen in einem Aufruf stecken. Bei Muell im Strom wird auf
 * 0x7F ... 0x00 resynchronisiert, ohne bereits empfangene, noch unvollstaendige Bytes zu
 * verwerfen.
 */
class Pce323FrameDecoder {

    private val buffer = ArrayDeque<Byte>()

    /** Zaehlt verworfene Frame-Kandidaten: falscher Endmarker oder unplausibler Messwert. */
    var decodeErrors: Int = 0
        private set

    private var lastValidLevel: Double? = null

    fun feed(bytes: ByteArray): List<MeterFrame> {
        buffer.addAll(bytes.toList())
        val frames = mutableListOf<MeterFrame>()

        while (true) {
            while (buffer.isNotEmpty() && buffer.first() != START_MARKER) {
                buffer.removeFirst()
            }
            if (buffer.size < FRAME_SIZE) break

            val candidate = ByteArray(FRAME_SIZE) { buffer[it] }
            if (candidate[FRAME_SIZE - 1] != END_MARKER) {
                // Kein gueltiger Frame an dieser Position: nur den Startmarker verwerfen und
                // erneut resynchronisieren, statt den ganzen Kandidaten wegzuwerfen - ein
                // echter Frame koennte innerhalb dieses Fensters erst noch beginnen.
                buffer.removeFirst()
                decodeErrors++
                continue
            }

            repeat(FRAME_SIZE) { buffer.removeFirst() }
            val frame = decode(candidate)
            if (frame == null) {
                decodeErrors++
            } else {
                frames.add(frame)
            }
        }

        return frames
    }

    private fun decode(raw: ByteArray): MeterFrame? {
        val level = (((raw[1].toInt() and 0xFF) shl 8) or (raw[2].toInt() and 0xFF)) / 10.0
        if (level < MIN_PLAUSIBLE_LEVEL || level > MAX_PLAUSIBLE_LEVEL) {
            return null
        }

        val weightingFlags = raw[3].toInt()
        val rangeFlags = raw[4].toInt()

        val weighting = if (weightingFlags and 0b01 == 0) Weighting.A else Weighting.C
        val timeWeighting = if (weightingFlags and 0b10 == 0) TimeWeighting.FAST else TimeWeighting.SLOW
        val range = when (rangeFlags and 0b11) {
            0 -> MeasurementRange.RANGE_30_130
            1 -> MeasurementRange.RANGE_30_80
            2 -> MeasurementRange.RANGE_50_100
            else -> MeasurementRange.RANGE_80_130
        }
        val holdMax = rangeFlags and 0b0100 != 0
        val holdMin = rangeFlags and 0b1000 != 0

        // Impulsschall ist real - grosse Spruenge werden markiert, nicht verworfen.
        val previous = lastValidLevel
        val largeJump = previous != null && abs(level - previous) > LARGE_JUMP_THRESHOLD_DB
        lastValidLevel = level

        return MeterFrame(
            level = level,
            weighting = weighting,
            timeWeighting = timeWeighting,
            range = range,
            holdMax = holdMax,
            holdMin = holdMin,
            receivedAt = Instant.now(),
            largeJump = largeJump,
        )
    }
}
