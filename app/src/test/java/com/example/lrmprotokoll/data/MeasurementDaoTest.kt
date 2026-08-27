package com.example.lrmprotokoll.data

import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PROMPT_M9A.md Aufgabe 1: [MeasurementDao.fuerSessionAbFlow] muss die Zeilenzahl tatsaechlich
 * begrenzen, nicht nur eine zusaetzliche, folgenlose Signatur neben [MeasurementDao.fuerSessionFlow]
 * sein - LiveCockpitCard.kt haengt seine gesamte Kostenersparnis daran, dass die Datenbank
 * selbst filtert statt "alles laden, dann im Speicher wegwerfen".
 *
 * Gegenprobe: wuerde die Query wie [MeasurementDao.fuerSessionFlow] implementiert (ohne die
 * `timestamp >= :ab`-Bedingung), muesste [begrenztAufWerteAbDerGrenze] fehlschlagen, weil dann
 * wieder alle 10 statt nur 5 Zeilen zurueckkaemen.
 *
 * Jede Testmethode nutzt eine eigene, weit auseinanderliegende sessionId (kein 1L/2L): die
 * Room-Datenbank hier ist ueber [LaermprotokollApp]/[AppContainer], also
 * `Room.databaseBuilder(...)` auf eine feste Datei aufgebaut, nicht in-memory-isoliert je
 * Testmethode - Zeilen aus einer frueher gelaufenen Methode koennen sonst in die Zaehlung einer
 * spaeteren hineinlaufen (am eigenen Leib erlebt: 10 erwartet, 11 erhalten, weil eine andere
 * Methode zuvor eine Zeile mit derselben sessionId angelegt hatte).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeasurementDaoTest {

    private fun dao() =
        ApplicationProvider.getApplicationContext<LaermprotokollApp>().container.database.measurementDao()

    private fun messwert(sessionId: Long, zeitMs: Long) =
        MeasurementEntity(sessionId = sessionId, timestamp = zeitMs, levelDb = 50.0, weighting = null, flags = 0)

    @Test
    fun begrenztAufWerteAbDerGrenze() = runBlocking {
        val dao = dao()
        val sessionId = 910_001L
        // 10 Werte über 10 Sekunden verteilt (0s, 1s, ..., 9s), die Grenze liegt genau in der
        // Mitte - nur die zweite Haelfte (5s..9s) darf zurueckkommen.
        dao.insertAll((0 until 10).map { messwert(sessionId, it * 1_000L) })

        val alles = dao.fuerSessionFlow(sessionId).first()
        val eingeschraenkt = dao.fuerSessionAbFlow(sessionId, ab = 5_000L).first()

        assertEquals("fuerSessionFlow liefert weiterhin die volle Session", 10, alles.size)
        assertEquals("fuerSessionAbFlow muss auf die Haelfte begrenzen", 5, eingeschraenkt.size)
        assertTrue(
            "keine Zeile vor der Grenze darf zurueckkommen",
            eingeschraenkt.all { it.timestamp >= 5_000L },
        )
    }

    @Test
    fun grenzeVorSessionbeginnLaedtWeiterhinAlles() = runBlocking {
        val dao = dao()
        val sessionId = 910_002L
        dao.insertAll((0 until 5).map { messwert(sessionId, it * 1_000L) })

        val eingeschraenkt = dao.fuerSessionAbFlow(sessionId, ab = -1L).first()

        assertEquals(5, eingeschraenkt.size)
    }

    @Test
    fun filtertWeiterhinNachSessionIdWieFuerSessionFlow() = runBlocking {
        val dao = dao()
        val sessionA = 910_003L
        val sessionB = 910_004L
        dao.insertAll(
            listOf(
                messwert(sessionId = sessionA, zeitMs = 0L),
                messwert(sessionId = sessionB, zeitMs = 0L),
            )
        )

        val nurSessionA = dao.fuerSessionAbFlow(sessionId = sessionA, ab = 0L).first()

        assertEquals(1, nurSessionA.size)
        assertEquals(sessionA, nurSessionA.single().sessionId)
    }
}
