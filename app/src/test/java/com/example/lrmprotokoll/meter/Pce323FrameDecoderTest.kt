package com.example.lrmprotokoll.meter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun buildFrame(
    levelTimesTen: Int,
    weighting: Weighting = Weighting.A,
    timeWeighting: TimeWeighting = TimeWeighting.FAST,
    range: MeasurementRange = MeasurementRange.RANGE_30_130,
    holdMax: Boolean = false,
    holdMin: Boolean = false,
): ByteArray {
    val hi = (levelTimesTen shr 8) and 0xFF
    val lo = levelTimesTen and 0xFF

    var flags3 = 0
    if (weighting == Weighting.C) flags3 = flags3 or 0b01
    if (timeWeighting == TimeWeighting.SLOW) flags3 = flags3 or 0b10

    var flags4 = when (range) {
        MeasurementRange.RANGE_30_130 -> 0
        MeasurementRange.RANGE_30_80 -> 1
        MeasurementRange.RANGE_50_100 -> 2
        MeasurementRange.RANGE_80_130 -> 3
    }
    if (holdMax) flags4 = flags4 or 0b0100
    if (holdMin) flags4 = flags4 or 0b1000

    return byteArrayOf(
        0x7F.toByte(),
        hi.toByte(),
        lo.toByte(),
        flags3.toByte(),
        flags4.toByte(),
        0x00.toByte(),
    )
}

class Pce323FrameDecoderTest {

    @Test
    fun saubererFrameDecodiertAlleFlagKombinationenKorrekt() {
        val holdCombos = listOf(false to false, true to false, false to true, true to true)

        for (range in MeasurementRange.entries) {
            for (weighting in Weighting.entries) {
                for (timeWeighting in TimeWeighting.entries) {
                    for ((holdMax, holdMin) in holdCombos) {
                        val decoder = Pce323FrameDecoder()
                        val bytes = buildFrame(
                            levelTimesTen = 635,
                            weighting = weighting,
                            timeWeighting = timeWeighting,
                            range = range,
                            holdMax = holdMax,
                            holdMin = holdMin,
                        )

                        val frames = decoder.feed(bytes)

                        assertEquals(1, frames.size)
                        val frame = frames.single()
                        assertEquals(63.5, frame.level, 0.0001)
                        assertEquals(weighting, frame.weighting)
                        assertEquals(timeWeighting, frame.timeWeighting)
                        assertEquals(range, frame.range)
                        assertEquals(holdMax, frame.holdMax)
                        assertEquals(holdMin, frame.holdMin)
                        assertEquals(0, decoder.decodeErrors)
                    }
                }
            }
        }
    }

    @Test
    fun zweiFramesInEinemPaket() {
        val decoder = Pce323FrameDecoder()
        val frame1 = buildFrame(levelTimesTen = 500)
        val frame2 = buildFrame(levelTimesTen = 520)

        val frames = decoder.feed(frame1 + frame2)

        assertEquals(2, frames.size)
        assertEquals(50.0, frames[0].level, 0.0001)
        assertEquals(52.0, frames[1].level, 0.0001)
        assertEquals(0, decoder.decodeErrors)
    }

    @Test
    fun frameUeberZweiPaketeVerteilt() {
        val decoder = Pce323FrameDecoder()
        val bytes = buildFrame(levelTimesTen = 611)

        val framesFromFirst = decoder.feed(bytes.copyOfRange(0, 3))
        assertTrue(framesFromFirst.isEmpty())

        val framesFromSecond = decoder.feed(bytes.copyOfRange(3, 6))
        assertEquals(1, framesFromSecond.size)
        assertEquals(61.1, framesFromSecond[0].level, 0.0001)
        assertEquals(0, decoder.decodeErrors)
    }

    @Test
    fun muellVorErstemFrameWirdResynchronisiert() {
        val decoder = Pce323FrameDecoder()
        val garbage = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val validFrame = buildFrame(levelTimesTen = 555)

        val frames = decoder.feed(garbage + validFrame)

        assertEquals(1, frames.size)
        assertEquals(55.5, frames[0].level, 0.0001)
    }

    @Test
    fun abgeschnittenerFrameAmPufferendeGehtBeimNaechstenPaketNichtVerloren() {
        val decoder = Pce323FrameDecoder()
        val completeFrame = buildFrame(levelTimesTen = 480)
        val nextFrame = buildFrame(levelTimesTen = 700)
        val truncated = nextFrame.copyOfRange(0, 4)

        val firstBatch = decoder.feed(completeFrame + truncated)
        assertEquals(1, firstBatch.size)
        assertEquals(48.0, firstBatch[0].level, 0.0001)

        val secondBatch = decoder.feed(nextFrame.copyOfRange(4, 6))
        assertEquals(1, secondBatch.size)
        assertEquals(70.0, secondBatch[0].level, 0.0001)
        assertEquals(0, decoder.decodeErrors)
    }

    @Test
    fun unplausiblerWertWirdVerworfenUndZaehltAlsDecodeError() {
        val decoder = Pce323FrameDecoder()
        val zuNiedrig = buildFrame(levelTimesTen = 100) // 10,0 dB
        val zuHoch = buildFrame(levelTimesTen = 1500) // 150,0 dB

        val frames = decoder.feed(zuNiedrig + zuHoch)

        assertTrue(frames.isEmpty())
        assertEquals(2, decoder.decodeErrors)
    }

    @Test
    fun falscherEndmarkerWirdAlsDecodeErrorGezaehltUndResynchronisiert() {
        val decoder = Pce323FrameDecoder()
        val korrupt = buildFrame(levelTimesTen = 500).also { it[5] = 0x01 }
        val validFrame = buildFrame(levelTimesTen = 480)

        val frames = decoder.feed(korrupt + validFrame)

        assertEquals(1, frames.size)
        assertEquals(48.0, frames[0].level, 0.0001)
        assertEquals(1, decoder.decodeErrors)
    }

    @Test
    fun grosserSprungWirdMarkiertAberNichtVerworfen() {
        val decoder = Pce323FrameDecoder()
        val first = buildFrame(levelTimesTen = 500) // 50,0 dB
        val second = buildFrame(levelTimesTen = 950) // 95,0 dB, Sprung 45 dB

        val frames = decoder.feed(first + second)

        assertEquals(2, frames.size)
        assertFalse(frames[0].largeJump)
        assertTrue(frames[1].largeJump)
        assertEquals(0, decoder.decodeErrors)
    }

    @Test
    fun kleinerSprungWirdNichtMarkiert() {
        val decoder = Pce323FrameDecoder()
        val first = buildFrame(levelTimesTen = 500) // 50,0 dB
        val second = buildFrame(levelTimesTen = 530) // 53,0 dB, Sprung 3 dB

        val frames = decoder.feed(first + second)

        assertFalse(frames[0].largeJump)
        assertFalse(frames[1].largeJump)
    }
}
