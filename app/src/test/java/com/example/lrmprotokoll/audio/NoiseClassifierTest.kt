package com.example.lrmprotokoll.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Review-Befund aus PR #19: die Abtastrate fuer die Klassifikation muss aus dem WAV-Header der
 * Datei kommen, die gerade klassifiziert wird, nicht aus der AKTUELLEN Einstellung -
 * andernfalls interpretiert der Klassifikator eine alte 44,1-kHz-Aufnahme still falsch, wenn
 * die Einstellung inzwischen auf 16 kHz umgestellt wurde (z.B. beim nachtraeglichen "Als
 * Referenz lernen"). Reiner JUnit-Test, keine Android-/Robolectric-Abhaengigkeit noetig, da
 * wavSampleRateOrFallback() nur ByteArray-Arithmetik ist.
 */
class NoiseClassifierTest {

    private fun headerWithSampleRate(rate: Int): ByteArray {
        val header = ByteArray(44)
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(24, rate)
        return header
    }

    @Test
    fun liestAbtastrateAusHeaderStattAusFallback() {
        // Der zentrale Fall: der Header sagt 44100, der "aktuelle" Fallback-Wert waere 16000 -
        // das Ergebnis muss die Header-Rate sein, sonst waere die Funktion nutzlos.
        val header = headerWithSampleRate(44_100)

        val result = wavSampleRateOrFallback(header, fallback = 16_000)

        assertEquals(44_100, result)
    }

    @Test
    fun faelltBeiZuKurzemHeaderAufFallbackZurueck() {
        val tooShort = ByteArray(20)

        val result = wavSampleRateOrFallback(tooShort, fallback = 16_000)

        assertEquals(16_000, result)
    }

    @Test
    fun faelltBeiUnplausiblerRateAufFallbackZurueck() {
        // 0 Hz (z.B. ein beschaedigter oder von skip() nie befuellter Header) ist kein
        // plausibler Wert und darf nicht unveraendert an den Klassifikator weitergereicht werden.
        val header = headerWithSampleRate(0)

        val result = wavSampleRateOrFallback(header, fallback = 16_000)

        assertEquals(16_000, result)
    }

    /**
     * KI-Umbau Etappe 1.1: [wavBitsPerSample] liefert die im Header dokumentierte Bit-Tiefe -
     * Teil der geforderten Diagnosewerte (Auftrag Abschnitt 1.1).
     */
    @Test
    fun liestBitTiefeAusHeader() {
        val header = ByteArray(44)
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putShort(34, 16)

        assertEquals(16, wavBitsPerSample(header))
    }

    @Test
    fun liefertNullBeiZuKurzemHeaderStattEinesGeratenenWerts() {
        val tooShort = ByteArray(20)

        assertNull(wavBitsPerSample(tooShort))
    }
}
