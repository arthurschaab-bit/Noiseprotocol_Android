package com.example.lrmprotokoll.report

import com.example.lrmprotokoll.data.MeasurementEntity
import com.example.lrmprotokoll.data.SessionEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** Owner-Entscheidung 10.10.2026: Mikrofonwerte fliessen in keine Berechnung ein. */
class MikrofonTrennungTest {
    private fun session(id: Long, adresse: String) =
        SessionEntity(id = id, startedAt = 0L, endedAt = null, deviceAddress = adresse, deviceName = "", weighting = null, timeWeighting = null)

    private fun wert(sessionId: Long, db: Double) =
        MeasurementEntity(sessionId = sessionId, timestamp = 1_000L, levelDb = db, weighting = null, flags = 0)

    @Test
    fun mikrofonSessionsLandenNurInDerMikrofonListe() {
        val sessions = listOf(session(1, "AA:BB:CC:DD:EE:FF"), session(2, ""))
        val getrennt = trenneMesswerte(listOf(wert(1, 60.0), wert(2, 99.0)), sessions)
        assertEquals(listOf(60.0), getrennt.kalibriert.map { it.levelDb })
        assertEquals(listOf(99.0), getrennt.mikrofon.map { it.levelDb })
    }
}
