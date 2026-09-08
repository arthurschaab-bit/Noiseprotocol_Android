package com.example.lrmprotokoll.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FastScrollerTest {

    @Test
    fun fractionWirdAufGesamtenIndexbereichAbgebildet() {
        assertEquals(0, fastScrollTargetIndex(0f, 20_000))
        assertEquals(10_000, fastScrollTargetIndex(0.5f, 20_000))
        assertEquals(19_999, fastScrollTargetIndex(1f, 20_000))
    }

    @Test
    fun fractionUndLeereListenWerdenSicherBegrenzt() {
        assertEquals(0, fastScrollTargetIndex(-1f, 20_000))
        assertEquals(19_999, fastScrollTargetIndex(2f, 20_000))
        assertEquals(0, fastScrollTargetIndex(0.75f, 0))
        assertEquals(0, fastScrollTargetIndex(0.75f, 1))
    }
}
