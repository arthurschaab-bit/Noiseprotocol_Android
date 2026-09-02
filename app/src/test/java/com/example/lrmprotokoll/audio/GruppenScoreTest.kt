package com.example.lrmprotokoll.audio

import com.example.lrmprotokoll.data.KlassifikationsRohdaten
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KI-Umbau Etappe 2.3: Tests fuer [berechneGruppenScores] - reine JVM-Tests, kein Geraet noetig
 * (Arbeitsweise-Regel 3).
 */
class GruppenScoreTest {

    private fun rohdatenMitFrames(vararg frames: Map<Int, Int>): KlassifikationsRohdaten {
        val klassenIndizes = ROHDATEN_KLASSEN_INDIZES
        val frameScores = ByteArray(frames.size * klassenIndizes.size)
        frames.forEachIndexed { frameIndex, werte ->
            klassenIndizes.forEachIndexed { position, index ->
                frameScores[frameIndex * klassenIndizes.size + position] = (werte[index] ?: 0).toByte()
            }
        }
        return KlassifikationsRohdaten(
            recordId = 1,
            modellVersion = "test",
            klassifiziertAm = 0,
            frameAnzahl = frames.size,
            frameDauerMs = 960,
            frameHopMs = 480,
            klassenIndizes = klassenIndizes,
            frameScores = frameScores,
            topKlassen = "",
        )
    }

    @Test
    fun leereRohdatenErgebenLeeresErgebnis() {
        val rohdaten = rohdatenMitFrames()

        assertEquals(0, berechneGruppenScores(rohdaten).size)
    }

    @Test
    fun einzelneKernKlasseAufMaximumErgibtGruppenScoreNaheEins() {
        // Index 413 = "Hammer", Kern, Gewicht 1.0.
        val rohdaten = rohdatenMitFrames(mapOf(413 to 255))

        val scores = berechneGruppenScores(rohdaten)

        assertEquals(1, scores.size)
        assertTrue(abs(scores[0] - 1.0f) < 0.01f)
    }

    @Test
    fun keineRelevanteKlasseErgibtGruppenScoreNull() {
        // Index 494 = "Silence" - eine Ausschluss-Klasse, kein Eintrag in BAULAERM_KLASSEN.
        val rohdaten = rohdatenMitFrames(mapOf(494 to 255))

        val scores = berechneGruppenScores(rohdaten)

        assertEquals(0f, scores[0], 0.001f)
    }

    @Test
    fun mehrereTrefferKombinierenSichAlsNoisyOrNichtAlsSumme() {
        // Zwei Kontext-Klassen (Gewicht 0.5) je bei vollem Score: eine Summe waere 0.5+0.5=1.0,
        // noisy-OR ergibt 1 - (1-0.5)*(1-0.5) = 0.75 - das ist genau der Punkt der Aufgabe.
        val rohdaten = rohdatenMitFrames(mapOf(310 to 255, 337 to 255)) // Truck + Engine

        val scores = berechneGruppenScores(rohdaten)

        assertTrue(
            "Erwartet 0.75 (noisy-OR), nicht 1.0 (Summe) - war ${scores[0]}",
            abs(scores[0] - 0.75f) < 0.01f,
        )
    }

    @Test
    fun vorzeichenbehafteteByteWerteWerdenKorrektAlsUnsignedInterpretiert() {
        // 255 als signed Byte ist -1 - ohne "and 0xFF" wuerde das als (Betrags-)hoher, aber
        // NEGATIVER Wert interpretiert und den Score verfaelschen statt auf ~1.0 zu kommen.
        assertEquals(1.0f, quantisierterScore(byteArrayOf(255.toByte()), 0), 0.01f)
        assertEquals(0.0f, quantisierterScore(byteArrayOf(0), 0), 0.001f)
        assertEquals(0.5f, quantisierterScore(byteArrayOf(128.toByte()), 0), 0.01f)
    }

    @Test
    fun mehrereFramesWerdenUnabhaengigVoneinanderBerechnet() {
        val rohdaten = rohdatenMitFrames(
            mapOf(413 to 255), // Frame 0: volle Kernklasse
            mapOf(413 to 0), // Frame 1: still
        )

        val scores = berechneGruppenScores(rohdaten)

        assertEquals(2, scores.size)
        assertTrue(scores[0] > 0.9f)
        assertEquals(0f, scores[1], 0.001f)
    }
}
