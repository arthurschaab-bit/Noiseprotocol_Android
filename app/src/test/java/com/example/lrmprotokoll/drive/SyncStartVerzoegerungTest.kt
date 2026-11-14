package com.example.lrmprotokoll.drive

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncStartVerzoegerungTest {
    private val start = 1_000_000L

    @Test
    fun direktNachDemDienststartWirdDieVolleSchonfristGewartet() {
        assertEquals(120_000L, SyncStartVerzoegerung.restMs(true, start, start))
    }

    @Test
    fun dieRestzeitSchrumpftMitDerZeitUndEndetNachZweiMinuten() {
        assertEquals(60_000L, SyncStartVerzoegerung.restMs(true, start, start + 60_000))
        assertEquals(0L, SyncStartVerzoegerung.restMs(true, start, start + 120_000))
        assertEquals(0L, SyncStartVerzoegerung.restMs(true, start, start + 500_000))
    }

    @Test
    fun ohneLaufendenDienstOderOhneGemerktenStartWirdNieGewartet() {
        assertEquals(0L, SyncStartVerzoegerung.restMs(false, start, start))
        assertEquals(0L, SyncStartVerzoegerung.restMs(true, 0L, start))
    }

    @Test
    fun einZurueckgestellteUhrVerzoegertNicht() {
        assertEquals(0L, SyncStartVerzoegerung.restMs(true, start, start - 5_000))
    }
}
