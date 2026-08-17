package com.example.lrmprotokoll.meter

import com.example.lrmprotokoll.meter.ble.Pce323Profile
import java.time.Instant
import kotlin.math.abs

private const val MIN_PLAUSIBLE_LEVEL = 20.0
private const val MAX_PLAUSIBLE_LEVEL = 140.0
private const val LARGE_JUMP_THRESHOLD_DB = 40.0

// Drei Positionen im sonst konstanten Header/Footer/Trailer sind erwiesenermassen variabel
// (Pce323Profile-Doc) und werden beim Resync als Wildcard behandelt (jeder Bytewert akzeptiert),
// nicht gegen eine feste Wertemenge geprueft: Ein noch unbekannter fuenfter Bereichs- oder
// dritter Bewertungswert soll den Rest des Frames (allen voran den Pegel) nicht ungueltig
// machen. Die eigentliche "kennen wir diesen Wert" Pruefung passiert erst in decode() - dort
// wird ein unerkannter Wert bewusst zu `null`, nie zu einem geratenen Default.

/**
 * Dekodiert den realen PCE-323-Frame-Strom, wie in M0 am Geraet ermittelt und in
 * docs/PROTOKOLL_PCE-323.md sowie [Pce323Profile] festgeschrieben - NICHT das im
 * Implementierungsplan (Abschnitt 2.2) angenommene, dort mittlerweile als widerlegt markierte
 * PCE-322A-Format.
 *
 * Logisches Frame, 23 Byte:
 *   [0..13]  Header (14 Byte, [Pce323Profile.FRAME_HEADER]), bis auf Offset 7 konstant -
 *            Offset 7 traegt den Messbereich (Annahme, siehe [Pce323Profile])
 *   [14..17] Messwert, IEEE-754 float32 BIG ENDIAN, Wert direkt in dB
 *   [18..19] Footer, bis auf Offset 19 konstant - Offset 19 traegt Fast/Slow (Annahme)
 *   [20..22] Trailer, bis auf Offset 20 konstant - Offset 20 traegt A/C-Bewertung (Annahme)
 *
 * Arbeitet byteweise ueber einen Ringpuffer statt paketweise: Das Geraet liefert dieses Frame
 * bei Default-MTU in ZWEI BLE-Notifications (20 + 3 Byte, siehe M0), und der Puffer muss auch
 * beliebige andere Fragmentierung (ein Byte pro Aufruf, mehrere Frames pro Aufruf) verlustfrei
 * zusammensetzen. Sync-Anker sind die 20 (von 23) konstant gebliebenen Bytes aus Header, Footer
 * und Trailer - deutlich robuster gegen Zufallstreffer als ein 1-Byte-Marker. Die drei
 * variablen Positionen werden beim Resync gegen die jeweils bekannte kleine Wertemenge
 * geprueft (nicht gegen "beliebiges Byte"), damit echte Bitfehler weiterhin als Decode-Fehler
 * erkannt werden.
 */
class Pce323FrameDecoder {

    private val buffer = ArrayDeque<Byte>()

    /** Zaehlt verworfene Frame-Kandidaten: falscher Footer/Trailer oder unplausibler Messwert. */
    var decodeErrors: Int = 0
        private set

    private var lastValidLevel: Double? = null

    /**
     * Verwirft den gesamten Decoder-Zustand. Vor jedem neuen Verbindungsaufbau aufzurufen.
     *
     * Ohne diesen Schnitt schleppt der Decoder ueber einen Verbindungsabbruch hinweg drei Dinge
     * mit: ein angefangenes Frame im Puffer (der Regelfall, das Geraet sendet 20 + 3 Byte),
     * [lastValidLevel] und [decodeErrors]. Folge waeren kuenstliche Decode-Fehler bei jedem
     * Reconnect - was mehr als Kosmetik ist, weil die Fehlerrate laut Plan Abschnitt 5.5 als
     * Gesundheitssignal den DEGRADED-Zustand ausloest - und ein faelschlich gesetztes
     * [MeterFrame.largeJump] auf dem ersten Frame danach, weil es gegen einen beliebig alten
     * Pegel verglichen wuerde statt gegen den Vorgaenger im selben Datenstrom.
     */
    fun reset() {
        buffer.clear()
        lastValidLevel = null
        decodeErrors = 0
    }

    fun feed(bytes: ByteArray): List<MeterFrame> {
        buffer.addAll(bytes.toList())
        val frames = mutableListOf<MeterFrame>()

        while (true) {
            val headerIndex = indexOfHeader()
            if (headerIndex == null) {
                // Kein vollstaendiger Header im Puffer. Nur das verwerfen, was garantiert
                // nicht mehr Anfang eines kuenftigen Headers sein kann - der Rest bleibt fuer
                // den naechsten feed()-Aufruf stehen (kein Datenverlust bei Fragmentierung
                // mitten im Header).
                val keep = longestHeaderPrefixSuffixLength()
                repeat(buffer.size - keep) { buffer.removeFirst() }
                break
            }
            repeat(headerIndex) { buffer.removeFirst() }

            if (buffer.size < Pce323Profile.FRAME_SIZE) break // Frame noch nicht vollstaendig

            val candidate = ByteArray(Pce323Profile.FRAME_SIZE) { buffer[it] }
            if (!footerAndTrailerMatch(candidate)) {
                // Header war ein Zufallstreffer oder der Frame ist beschaedigt: nur das erste
                // Byte verwerfen und neu resynchronisieren, statt den ganzen Kandidaten wegzuwerfen
                // - ein echter Frame koennte innerhalb dieses Fensters erst noch beginnen.
                buffer.removeFirst()
                decodeErrors++
                continue
            }

            repeat(Pce323Profile.FRAME_SIZE) { buffer.removeFirst() }
            val frame = decode(candidate)
            if (frame == null) {
                decodeErrors++
            } else {
                frames.add(frame)
            }
        }

        return frames
    }

    private fun indexOfHeader(): Int? {
        val header = Pce323Profile.FRAME_HEADER
        if (buffer.size < header.size) return null
        outer@ for (start in 0..buffer.size - header.size) {
            for (i in header.indices) {
                if (i == Pce323Profile.RANGE_BYTE_OFFSET) continue // Wildcard: Messbereich
                if (buffer[start + i] != header[i]) continue@outer
            }
            return start
        }
        return null
    }

    /** Laengste Endung des Puffers, die ein echtes Praefix von [Pce323Profile.FRAME_HEADER] ist. */
    private fun longestHeaderPrefixSuffixLength(): Int {
        val header = Pce323Profile.FRAME_HEADER
        val maxLen = minOf(buffer.size, header.size - 1)
        for (len in maxLen downTo 1) {
            val start = buffer.size - len
            var matches = true
            for (i in 0 until len) {
                if (i == Pce323Profile.RANGE_BYTE_OFFSET) continue // Wildcard: Messbereich
                if (buffer[start + i] != header[i]) {
                    matches = false
                    break
                }
            }
            if (matches) return len
        }
        return 0
    }

    private fun footerAndTrailerMatch(candidate: ByteArray): Boolean {
        val footerOffset = Pce323Profile.LEVEL_OFFSET + Pce323Profile.LEVEL_SIZE
        for (i in Pce323Profile.FRAME_FOOTER.indices) {
            val offset = footerOffset + i
            if (offset == Pce323Profile.TIME_WEIGHTING_BYTE_OFFSET) continue // Wildcard: Fast/Slow
            if (candidate[offset] != Pce323Profile.FRAME_FOOTER[i]) return false
        }
        val trailerOffset = footerOffset + Pce323Profile.FRAME_FOOTER.size
        for (i in Pce323Profile.FRAME_TRAILER.indices) {
            val offset = trailerOffset + i
            if (offset == Pce323Profile.WEIGHTING_BYTE_OFFSET) continue // Wildcard: A/C-Bewertung
            if (candidate[offset] != Pce323Profile.FRAME_TRAILER[i]) return false
        }
        return true
    }

    private fun decode(raw: ByteArray): MeterFrame? {
        val o = Pce323Profile.LEVEL_OFFSET
        val bits = ((raw[o].toInt() and 0xFF) shl 24) or
            ((raw[o + 1].toInt() and 0xFF) shl 16) or
            ((raw[o + 2].toInt() and 0xFF) shl 8) or
            (raw[o + 3].toInt() and 0xFF)
        val level = Float.fromBits(bits).toDouble()

        // NaN/Infinity kann ein gestoerter Funkstrom liefern, obwohl Header/Footer/Trailer
        // zufaellig passen - beides ist per Definition kein plausibler Pegel.
        if (level.isNaN() || level.isInfinite() || level < MIN_PLAUSIBLE_LEVEL || level > MAX_PLAUSIBLE_LEVEL) {
            return null
        }

        // Impulsschall ist real - grosse Spruenge werden markiert, nicht verworfen.
        val previous = lastValidLevel
        val largeJump = previous != null && abs(level - previous) > LARGE_JUMP_THRESHOLD_DB
        lastValidLevel = level

        // holdMax/holdMin sind weiterhin unbekannt - keine Aufzeichnung hat bislang ein
        // dediziertes Hold-Bit isoliert. weighting/timeWeighting/range beruhen auf einer
        // ANNAHME, die am realen Geraet noch nicht bestaetigt ist (Pce323Profile-Doc,
        // docs/PROTOKOLL_PCE-323.md Abschnitt 9) - ein unerkannter Bytewert liefert bewusst
        // `null` statt eines geratenen Defaults.
        return MeterFrame(
            level = level,
            weighting = decodeWeighting(raw[Pce323Profile.WEIGHTING_BYTE_OFFSET]),
            timeWeighting = decodeTimeWeighting(raw[Pce323Profile.TIME_WEIGHTING_BYTE_OFFSET]),
            range = decodeRange(raw[Pce323Profile.RANGE_BYTE_OFFSET]),
            holdMax = null,
            holdMin = null,
            receivedAt = Instant.now(),
            largeJump = largeJump,
        )
    }

    private fun decodeWeighting(byte: Byte): Weighting? = when (byte) {
        Pce323Profile.WEIGHTING_VALUE_A -> Weighting.A
        Pce323Profile.WEIGHTING_VALUE_C -> Weighting.C
        else -> null
    }

    private fun decodeTimeWeighting(byte: Byte): TimeWeighting? = when (byte) {
        Pce323Profile.TIME_WEIGHTING_VALUE_FAST -> TimeWeighting.FAST
        Pce323Profile.TIME_WEIGHTING_VALUE_SLOW -> TimeWeighting.SLOW
        else -> null
    }

    private fun decodeRange(byte: Byte): MeasurementRange? = when (byte) {
        Pce323Profile.RANGE_VALUE_30_130 -> MeasurementRange.RANGE_30_130
        Pce323Profile.RANGE_VALUE_30_80 -> MeasurementRange.RANGE_30_80
        Pce323Profile.RANGE_VALUE_50_100 -> MeasurementRange.RANGE_50_100
        Pce323Profile.RANGE_VALUE_80_130 -> MeasurementRange.RANGE_80_130
        else -> null
    }
}
