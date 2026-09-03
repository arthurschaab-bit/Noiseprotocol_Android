package com.example.lrmprotokoll.video

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Die A/V-Synchronisation aus M11 Etappe B (B.2a). Ein Fehler hier ist am Ergebnis nicht
 * offensichtlich - er zeigt sich als Knall neben dem Bild, und dann ist das Beweisvideo
 * wertlos.
 */
class VideoTonSynchronisationTest {

    @Test
    fun gleichzeitigerStartBrauchtWederSchnittNochStille() {
        val anlage = VideoTonSynchronisation.anlage(
            videoStartMs = 1_000_000, tonStartMs = 1_000_000, abtastrate = 44_100, kanaele = 1,
        )
        assertEquals(0L, anlage.ueberspringeBytes)
        assertEquals(0L, anlage.stilleBytes)
    }

    @Test
    fun frueherBeginnenderTonWirdVornGeschnitten() {
        // Der Ton lief 500 ms laenger als das Video - diese 500 ms muessen weg.
        val anlage = VideoTonSynchronisation.anlage(
            videoStartMs = 1_000_500, tonStartMs = 1_000_000, abtastrate = 44_100, kanaele = 1,
        )
        assertEquals(44_100 / 2 * 2L, anlage.ueberspringeBytes)
        assertEquals(0L, anlage.stilleBytes)
    }

    @Test
    fun spaeterBeginnenderTonBekommtStilleVorangestellt() {
        // Nicht den Ton vorziehen: Sonst ist das Geraeusch vor seiner Ursache zu hoeren.
        val anlage = VideoTonSynchronisation.anlage(
            videoStartMs = 1_000_000, tonStartMs = 1_000_250, abtastrate = 48_000, kanaele = 1,
        )
        assertEquals(0L, anlage.ueberspringeBytes)
        assertEquals(48_000 / 4 * 2L, anlage.stilleBytes)
    }

    @Test
    fun derVersatzLiegtImmerAufEinerFramegrenze() {
        // Ein um ein einzelnes Byte verschobener Stereostrom vertauscht die Kanaele.
        val anlage = VideoTonSynchronisation.anlage(
            videoStartMs = 1_000_333, tonStartMs = 1_000_000, abtastrate = 44_100, kanaele = 2,
        )
        assertEquals(0L, anlage.ueberspringeBytes % 4)
    }

    @Test
    fun zeitstempelKommenAusDerSamplePositionNichtAusDerUhr() {
        // Eine Sekunde Mono bei 44,1 kHz sind 88200 Bytes.
        assertEquals(0L, VideoTonSynchronisation.zeitstempelMikros(0, 44_100, 1))
        assertEquals(1_000_000L, VideoTonSynchronisation.zeitstempelMikros(88_200, 44_100, 1))
        assertEquals(500_000L, VideoTonSynchronisation.zeitstempelMikros(44_100, 44_100, 1))
    }

    @Test
    fun zeitstempelBeruecksichtigenDieKanalzahl() {
        // Dieselbe Byteposition ist bei Stereo nur die halbe Zeit.
        assertEquals(500_000L, VideoTonSynchronisation.zeitstempelMikros(88_200, 44_100, 2))
    }

    @Test
    fun dauerWirdAusBytesUndRateBerechnet() {
        assertEquals(1000L, VideoTonSynchronisation.dauerMs(88_200, 44_100, 1))
        assertEquals(3000L, VideoTonSynchronisation.dauerMs(3 * 88_200, 44_100, 1))
    }

    @Test
    fun eineUnbrauchbareAbtastrateWirftStattStillFalschZuRechnen() {
        // Lieber ein Fehler als ein Video mit falscher Tonhoehe.
        val fehler = runCatching { VideoTonSynchronisation.zeitstempelMikros(1000, 0, 1) }
        assertEquals(true, fehler.isFailure)
    }
}
