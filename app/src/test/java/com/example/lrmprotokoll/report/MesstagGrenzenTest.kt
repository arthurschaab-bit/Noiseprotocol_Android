package com.example.lrmprotokoll.report

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class MesstagGrenzenTest {
    private val zone = ZoneId.of("Europe/Berlin")

    @Test
    fun sommerzeitwechselHat23Stunden() {
        val tag = LocalDate.of(2026, 3, 29)
        val (von, bis) = messtagGrenzen(tag, zone)

        assertEquals(23L * 60 * 60 * 1000, bis - von)
        assertEquals(tag, lokalerMesstag(von, zone))
    }

    @Test
    fun winterzeitwechselHat25Stunden() {
        val tag = LocalDate.of(2026, 10, 25)
        val (von, bis) = messtagGrenzen(tag, zone)

        assertEquals(25L * 60 * 60 * 1000, bis - von)
        assertEquals(tag, lokalerMesstag(bis - 1, zone))
    }
}
