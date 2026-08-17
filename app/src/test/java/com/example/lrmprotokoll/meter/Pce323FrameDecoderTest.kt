package com.example.lrmprotokoll.meter

import com.example.lrmprotokoll.meter.ble.Pce323Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sollwerte aus docs/discovery/pce323_notify_frames_2026-08-17.txt (letzte Spalte), unabhaengig
 * per Python aus dem realen nRF-Connect-Log berechnet - nicht aus dem Decoder selbst abgeleitet.
 */
private val EXPECTED_LEVELS = doubleArrayOf(
    42.10, 42.10, 42.00, 42.00, 41.90, 41.90, 41.90, 41.80, 42.00, 42.10,
    42.10, 42.00, 42.00, 42.00, 42.00, 42.00, 42.10, 42.10, 42.10, 43.30,
    43.50, 44.00, 44.00, 44.00, 43.60, 43.30, 43.20, 43.20, 43.20, 43.50,
    43.70, 44.20, 44.50, 45.00, 45.90, 46.70, 48.30, 50.10, 51.50, 51.70,
    52.20, 53.10, 54.00, 53.40, 53.10, 52.50, 52.10, 51.30, 50.60, 51.00,
    50.50, 49.30, 48.40, 48.20, 47.80, 47.60, 47.20, 46.20, 45.50, 45.00,
    44.70, 44.20, 44.20, 43.80, 43.40, 43.30, 43.40, 43.50, 43.90, 44.20,
    43.90, 43.70, 43.20, 43.00, 42.70, 42.50, 42.40, 42.20, 42.20, 42.20,
    42.00, 42.00, 42.00, 41.90, 41.90, 42.00, 42.00, 42.00, 41.90, 41.90,
    42.00, 41.90, 41.90, 43.20, 45.40, 43.80, 43.00, 43.40, 43.10,
)

private fun loadRealFrames(): ByteArray {
    val stream = Pce323FrameDecoderTest::class.java.classLoader
        ?.getResourceAsStream("pce323_notify_frames_2026-08-17.bin")
        ?: error("Fixture pce323_notify_frames_2026-08-17.bin nicht auf dem Testklassenpfad")
    return stream.use { it.readBytes() }
}

private fun buildFrame(
    level: Float,
    rangeByte: Byte = Pce323Profile.RANGE_VALUE_30_130,
    timeWeightingByte: Byte = Pce323Profile.TIME_WEIGHTING_VALUE_FAST,
    weightingByte: Byte = Pce323Profile.WEIGHTING_VALUE_A,
): ByteArray {
    val bits = level.toRawBits()
    val levelBytes = byteArrayOf(
        ((bits ushr 24) and 0xFF).toByte(),
        ((bits ushr 16) and 0xFF).toByte(),
        ((bits ushr 8) and 0xFF).toByte(),
        (bits and 0xFF).toByte(),
    )
    val header = Pce323Profile.FRAME_HEADER.copyOf().also { it[Pce323Profile.RANGE_BYTE_OFFSET] = rangeByte }
    val footer = Pce323Profile.FRAME_FOOTER.copyOf().also { it[1] = timeWeightingByte }
    val trailer = Pce323Profile.FRAME_TRAILER.copyOf().also { it[0] = weightingByte }
    return header + levelBytes + footer + trailer
}

class Pce323FrameDecoderTest {

    @Test
    fun alle99EchtenFramesWerdenKorrektDekodiert() {
        val raw = loadRealFrames()
        assertEquals(99 * Pce323Profile.FRAME_SIZE, raw.size)

        val decoder = Pce323FrameDecoder()
        val frames = decoder.feed(raw)

        assertEquals(99, frames.size)
        assertEquals(0, decoder.decodeErrors)
        frames.forEachIndexed { index, frame ->
            assertEquals("Frame $index", EXPECTED_LEVELS[index], frame.level, 0.001)
        }
    }

    @Test
    fun reassemblyAus20Plus3ByteNotificationsLiefertIdentischesErgebnis() {
        val raw = loadRealFrames()
        val decoder = Pce323FrameDecoder()
        val frames = mutableListOf<MeterFrame>()

        var offset = 0
        while (offset < raw.size) {
            frames += decoder.feed(raw.copyOfRange(offset, offset + 20))
            frames += decoder.feed(raw.copyOfRange(offset + 20, offset + 23))
            offset += Pce323Profile.FRAME_SIZE
        }

        assertEquals(99, frames.size)
        assertEquals(0, decoder.decodeErrors)
        frames.forEachIndexed { index, frame ->
            assertEquals("Frame $index", EXPECTED_LEVELS[index], frame.level, 0.001)
        }
    }

    @Test
    fun alle2277ByteAmStueckInEinemAufruf() {
        val raw = loadRealFrames()
        val decoder = Pce323FrameDecoder()

        val frames = decoder.feed(raw)

        assertEquals(99, frames.size)
    }

    @Test
    fun byteweiseEinzelfuetterungLiefertAlle99Frames() {
        val raw = loadRealFrames()
        val decoder = Pce323FrameDecoder()
        val frames = mutableListOf<MeterFrame>()

        for (b in raw) {
            frames += decoder.feed(byteArrayOf(b))
        }

        assertEquals(99, frames.size)
        assertEquals(0, decoder.decodeErrors)
    }

    @Test
    fun muellVorErstemFrameWirdResynchronisiertOhneDatenverlust() {
        val decoder = Pce323FrameDecoder()
        val garbage = byteArrayOf(0x01, 0x02, 0x03, 0xFF.toByte(), 0x10, 0x20)
        val validFrame = buildFrame(50.0f)

        val frames = decoder.feed(garbage + validFrame)

        assertEquals(1, frames.size)
        assertEquals(50.0, frames[0].level, 0.001)
    }

    @Test
    fun verfaelschtesFooterByteWirdVerworfenUndZaehltEinenDecodeError() {
        val decoder = Pce323FrameDecoder()
        val corrupted = buildFrame(50.0f).also {
            it[Pce323Profile.LEVEL_OFFSET + Pce323Profile.LEVEL_SIZE] = 0x00
        }
        val validFrame = buildFrame(48.0f)

        val frames = decoder.feed(corrupted + validFrame)

        assertEquals(1, frames.size)
        assertEquals(48.0, frames[0].level, 0.001)
        assertEquals(1, decoder.decodeErrors)
    }

    @Test
    fun unendlicherMesswertWirdVerworfen() {
        val decoder = Pce323FrameDecoder()

        val frames = decoder.feed(buildFrame(Float.POSITIVE_INFINITY))

        assertTrue(frames.isEmpty())
        assertEquals(1, decoder.decodeErrors)
    }

    @Test
    fun nanMesswertWirdVerworfen() {
        val decoder = Pce323FrameDecoder()

        val frames = decoder.feed(buildFrame(Float.NaN))

        assertTrue(frames.isEmpty())
        assertEquals(1, decoder.decodeErrors)
    }

    @Test
    fun unplausiblerWertAusserhalbBereichWirdVerworfen() {
        val decoder = Pce323FrameDecoder()
        val zuNiedrig = buildFrame(10.0f)
        val zuHoch = buildFrame(150.0f)

        val frames = decoder.feed(zuNiedrig + zuHoch)

        assertTrue(frames.isEmpty())
        assertEquals(2, decoder.decodeErrors)
    }

    @Test
    fun grosserSprungWirdMarkiertAberNichtVerworfen() {
        val decoder = Pce323FrameDecoder()
        val first = buildFrame(50.0f)
        val second = buildFrame(95.0f) // Sprung 45 dB

        val frames = decoder.feed(first + second)

        assertEquals(2, frames.size)
        assertFalse(frames[0].largeJump)
        assertTrue(frames[1].largeJump)
    }

    @Test
    fun kleinerSprungWirdNichtMarkiert() {
        val decoder = Pce323FrameDecoder()
        val first = buildFrame(50.0f)
        val second = buildFrame(53.0f) // Sprung 3 dB

        val frames = decoder.feed(first + second)

        assertFalse(frames[0].largeJump)
        assertFalse(frames[1].largeJump)
    }

    @Test
    fun holdMaxUndHoldMinBleibenUnbekannt() {
        val decoder = Pce323FrameDecoder()

        val frame = decoder.feed(buildFrame(50.0f)).single()

        assertEquals(null, frame.holdMax)
        assertEquals(null, frame.holdMin)
    }

    @Test
    fun alleVierMessbereichsByteswerteWerdenGemaessAnnahmeDekodiert() {
        val decoder = Pce323FrameDecoder()
        val erwartet = mapOf(
            Pce323Profile.RANGE_VALUE_30_130 to MeasurementRange.RANGE_30_130,
            Pce323Profile.RANGE_VALUE_30_80 to MeasurementRange.RANGE_30_80,
            Pce323Profile.RANGE_VALUE_50_100 to MeasurementRange.RANGE_50_100,
            Pce323Profile.RANGE_VALUE_80_130 to MeasurementRange.RANGE_80_130,
        )

        erwartet.forEach { (byte, range) ->
            val frame = decoder.feed(buildFrame(50.0f, rangeByte = byte)).single()
            assertEquals("Bereich fuer Byte $byte", range, frame.range)
        }
    }

    @Test
    fun unbekannterMessbereichsByteliefertNullStattFalschenDefaults() {
        val decoder = Pce323FrameDecoder()

        val frame = decoder.feed(buildFrame(50.0f, rangeByte = 0x07)).single()

        assertEquals(null, frame.range)
    }

    @Test
    fun beideZeitbewertungsWerteWerdenGemaessAnnahmeDekodiert() {
        val decoder = Pce323FrameDecoder()

        val fast = decoder.feed(
            buildFrame(50.0f, timeWeightingByte = Pce323Profile.TIME_WEIGHTING_VALUE_FAST)
        ).single()
        val slow = decoder.feed(
            buildFrame(50.0f, timeWeightingByte = Pce323Profile.TIME_WEIGHTING_VALUE_SLOW)
        ).single()

        assertEquals(TimeWeighting.FAST, fast.timeWeighting)
        assertEquals(TimeWeighting.SLOW, slow.timeWeighting)
    }

    @Test
    fun unbekannterZeitbewertungsByteliefertNullStattFalschenDefaults() {
        val decoder = Pce323FrameDecoder()

        val frame = decoder.feed(buildFrame(50.0f, timeWeightingByte = 0x00)).single()

        assertEquals(null, frame.timeWeighting)
    }

    @Test
    fun beideBewertungsWerteWerdenGemaessAnnahmeDekodiert() {
        val decoder = Pce323FrameDecoder()

        val a = decoder.feed(
            buildFrame(50.0f, weightingByte = Pce323Profile.WEIGHTING_VALUE_A)
        ).single()
        val c = decoder.feed(
            buildFrame(50.0f, weightingByte = Pce323Profile.WEIGHTING_VALUE_C)
        ).single()

        assertEquals(Weighting.A, a.weighting)
        assertEquals(Weighting.C, c.weighting)
    }

    @Test
    fun unbekannterBewertungsByteliefertNullStattFalschenDefaults() {
        val decoder = Pce323FrameDecoder()

        val frame = decoder.feed(buildFrame(50.0f, weightingByte = 0x00)).single()

        assertEquals(null, frame.weighting)
    }

    @Test
    fun resetVerwirftAngefangenesFrameUndVerhindertKuenstlicheDecodeErrors() {
        val decoder = Pce323FrameDecoder()

        // Verbindungsabbruch mitten im Frame: die ersten 20 Byte sind angekommen, die
        // restlichen 3 nicht - genau der Regelfall bei Default-MTU.
        val angefangen = buildFrame(50.0f).copyOfRange(0, 20)
        assertEquals(0, decoder.feed(angefangen).size)

        decoder.reset()

        // Nach dem Reconnect beginnt ein sauberer Strom. Ohne reset() haette der alte
        // Teilframe hier einen verworfenen Kandidaten und damit einen Decode-Fehler erzeugt.
        val frames = decoder.feed(buildFrame(48.0f))
        assertEquals(1, frames.size)
        assertEquals(48.0, frames[0].level, 0.001)
        assertEquals(0, decoder.decodeErrors)
    }

    @Test
    fun resetSetztDenPegelvergleichZurueckSodassKeinFalscherSprungGemeldetWird() {
        val decoder = Pce323FrameDecoder()

        assertEquals(30.0, decoder.feed(buildFrame(30.0f))[0].level, 0.001)
        decoder.reset()

        // 100 dB liegen mehr als 40 dB ueber dem letzten Wert VOR dem Abbruch. Nach dem
        // Reconnect ist das kein Sprung im Signal, sondern nur eine Luecke - largeJump
        // darf deshalb nicht gesetzt sein.
        val frames = decoder.feed(buildFrame(100.0f))
        assertEquals(1, frames.size)
        assertFalse(frames[0].largeJump)
    }

    @Test
    fun resetSetztDenFehlerzaehlerZurueck() {
        val decoder = Pce323FrameDecoder()

        val kaputt = buildFrame(45.0f)
        kaputt[18] = 0x00 // Footer verfaelschen
        decoder.feed(kaputt)
        assertTrue(decoder.decodeErrors > 0)

        decoder.reset()
        assertEquals(0, decoder.decodeErrors)
    }
}
