package com.example.lrmprotokoll.meter

import com.example.lrmprotokoll.meter.ble.Pce323Profile
import java.time.Instant
import kotlin.math.abs

private const val MIN_PLAUSIBLE_LEVEL = 20.0
private const val MAX_PLAUSIBLE_LEVEL = 140.0
private const val LARGE_JUMP_THRESHOLD_DB = 40.0

/**
 * Dekodiert den realen PCE-323-Frame-Strom, wie in M0 am Geraet ermittelt und in
 * docs/PROTOKOLL_PCE-323.md sowie [Pce323Profile] festgeschrieben - NICHT das im
 * Implementierungsplan (Abschnitt 2.2) angenommene, dort mittlerweile als widerlegt markierte
 * PCE-322A-Format.
 *
 * Logisches Frame, 23 Byte:
 *   [0..13]  konstanter Header (14 Byte, [Pce323Profile.FRAME_HEADER])
 *   [14..17] Messwert, IEEE-754 float32 BIG ENDIAN, Wert direkt in dB
 *   [18..19] konstanter Footer (2 Byte)
 *   [20..22] konstanter Trailer (3 Byte, Funktion ungeklaert)
 *
 * Arbeitet byteweise ueber einen Ringpuffer statt paketweise: Das Geraet liefert dieses Frame
 * bei Default-MTU in ZWEI BLE-Notifications (20 + 3 Byte, siehe M0), und der Puffer muss auch
 * beliebige andere Fragmentierung (ein Byte pro Aufruf, mehrere Frames pro Aufruf) verlustfrei
 * zusammensetzen. Sync-Anker ist der 14 Byte lange konstante Header, nicht ein einzelnes
 * Markerbyte - deutlich robuster gegen Zufallstreffer als ein 1-Byte-Marker.
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
            if (candidate[footerOffset + i] != Pce323Profile.FRAME_FOOTER[i]) return false
        }
        val trailerOffset = footerOffset + Pce323Profile.FRAME_FOOTER.size
        for (i in Pce323Profile.FRAME_TRAILER.indices) {
            if (candidate[trailerOffset + i] != Pce323Profile.FRAME_TRAILER[i]) return false
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

        // weighting/timeWeighting/range/holdMax/holdMin sind unbekannt (siehe MeterFrame-Doc) -
        // das reale Protokoll liefert dafuer keine bekannte Kodierung.
        return MeterFrame(
            level = level,
            weighting = null,
            timeWeighting = null,
            range = null,
            holdMax = null,
            holdMin = null,
            receivedAt = Instant.now(),
            largeJump = largeJump,
        )
    }
}
