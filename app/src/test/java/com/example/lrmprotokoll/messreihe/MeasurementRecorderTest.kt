package com.example.lrmprotokoll.messreihe

import com.example.lrmprotokoll.alert.TestUhr
import com.example.lrmprotokoll.data.ConnectionEventDao
import com.example.lrmprotokoll.data.ConnectionEventEntity
import com.example.lrmprotokoll.data.ConnectionEventType
import com.example.lrmprotokoll.data.MeasurementDao
import com.example.lrmprotokoll.data.MeasurementEntity
import com.example.lrmprotokoll.data.MeasurementFlags
import com.example.lrmprotokoll.data.SessionDao
import com.example.lrmprotokoll.data.SessionEntity
import com.example.lrmprotokoll.meter.BoundDevice
import com.example.lrmprotokoll.meter.ConnectionState
import com.example.lrmprotokoll.meter.MeterFrame
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prueft die Sitzungs- und Messwertaufzeichnung aus Plan 8.1/8.2 gegen Fakes - ohne Room, ohne
 * echte Zeit, wie AlarmCoordinatorTest fuer M5.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeasurementRecorderTest {

    private class FakeSessionDao : SessionDao {
        val zeilen = mutableMapOf<Long, SessionEntity>()
        private var naechsteId = 1L
        override suspend fun insert(session: SessionEntity): Long {
            val id = naechsteId++
            zeilen[id] = session.copy(id = id)
            return id
        }
        override suspend fun update(session: SessionEntity) { zeilen[session.id] = session }
        override suspend fun byId(id: Long): SessionEntity? = zeilen[id]
        override suspend fun offeneSession(): SessionEntity? = zeilen.values.firstOrNull { it.endedAt == null }
        override fun alle() = throw NotImplementedError("im Test nicht benoetigt")
    }

    private class FakeMeasurementDao : MeasurementDao {
        val geschrieben = mutableListOf<MeasurementEntity>()
        override suspend fun insertAll(messwerte: List<MeasurementEntity>) { geschrieben += messwerte }
        override suspend fun fuerSession(sessionId: Long) = geschrieben.filter { it.sessionId == sessionId }
        override suspend fun zwischen(von: Long, bis: Long) = geschrieben.filter { it.timestamp in von until bis }
        override suspend fun aelterAls(grenze: Long) = geschrieben.filter { it.timestamp < grenze }
        override suspend fun loescheAelterAls(grenze: Long) { geschrieben.removeAll { it.timestamp < grenze } }
        override suspend fun anzahl(): Int = geschrieben.size
    }

    private class FakeConnectionEventDao : ConnectionEventDao {
        val geschrieben = mutableListOf<ConnectionEventEntity>()
        override suspend fun insert(event: ConnectionEventEntity) { geschrieben += event }
        override suspend fun fuerSession(sessionId: Long) = geschrieben.filter { it.sessionId == sessionId }
    }

    private val sessionDao = FakeSessionDao()
    private val measurementDao = FakeMeasurementDao()
    private val connectionEventDao = FakeConnectionEventDao()
    private val uhr = TestUhr(Instant.parse("2026-08-19T09:00:00Z"))
    private val zustaende = MutableStateFlow(ConnectionState.IDLE)
    private val frames = MutableSharedFlow<MeterFrame>(extraBufferCapacity = 64)

    private val device = BoundDevice(address = "AA:BB:CC:DD:EE:FF", name = "PCE-323")

    private fun frame(level: Double, bewertungBestaetigt: Boolean = false) = MeterFrame(
        level = level, weighting = com.example.lrmprotokoll.meter.Weighting.A, timeWeighting = null,
        range = null, holdMax = false, holdMin = false, receivedAt = uhr.now(),
        modeAssumptionConfirmed = bewertungBestaetigt,
    )

    @Test
    fun startLegtEineSessionAnBevorDieBeobachtungBeginnt() = runTest(UnconfinedTestDispatcher()) {
        val recorder = MeasurementRecorder(
            zustaende, frames, sessionDao, measurementDao, connectionEventDao,
            scope = backgroundScope, now = uhr, flushInterval = Duration.ofSeconds(5),
        )
        recorder.start(device)

        assertEquals(1, sessionDao.zeilen.size)
        val session = sessionDao.zeilen.values.single()
        assertEquals("AA:BB:CC:DD:EE:FF", session.deviceAddress)
        assertNull("Noch nicht beendet", session.endedAt)
        assertNull("Weighting unbestaetigt -> nicht gespeichert", session.weighting)
    }

    @Test
    fun frameOhneAktiveSessionWirdVerworfenNichtGepuffert() = runTest(UnconfinedTestDispatcher()) {
        // Kein start() aufgerufen - es darf trotzdem nichts geschehen, wenn zufaellig ein Frame
        // durchkommt (z.B. Restwerte aus einer vorherigen Subscription).
        val recorder = MeasurementRecorder(
            zustaende, frames, sessionDao, measurementDao, connectionEventDao,
            scope = backgroundScope, now = uhr,
        )
        frames.tryEmit(frame(55.0))
        runCurrent()

        assertTrue(measurementDao.geschrieben.isEmpty())
    }

    @Test
    fun messwerteWerdenGepuffertNichtSofortGeschrieben() = runTest(UnconfinedTestDispatcher()) {
        val recorder = MeasurementRecorder(
            zustaende, frames, sessionDao, measurementDao, connectionEventDao,
            scope = backgroundScope, now = uhr, flushInterval = Duration.ofSeconds(5),
        )
        recorder.start(device)

        frames.tryEmit(frame(55.0))
        runCurrent()

        assertTrue("Vor dem Flush darf noch nichts geschrieben sein", measurementDao.geschrieben.isEmpty())
    }

    @Test
    fun flushtNachDemZeitintervall() = runTest(UnconfinedTestDispatcher()) {
        val recorder = MeasurementRecorder(
            zustaende, frames, sessionDao, measurementDao, connectionEventDao,
            scope = backgroundScope, now = uhr, flushInterval = Duration.ofSeconds(5),
        )
        recorder.start(device)
        frames.tryEmit(frame(55.0))
        runCurrent()

        advanceTimeBy(5_001)
        runCurrent()

        assertEquals(1, measurementDao.geschrieben.size)
        assertEquals(55.0, measurementDao.geschrieben.single().levelDb, 0.0001)
    }

    @Test
    fun flushtSofortBeiErreichenDerBatchgroesse() = runTest(UnconfinedTestDispatcher()) {
        val recorder = MeasurementRecorder(
            zustaende, frames, sessionDao, measurementDao, connectionEventDao,
            scope = backgroundScope, now = uhr, flushInterval = Duration.ofMinutes(10), flushBatchSize = 3,
        )
        recorder.start(device)

        repeat(3) { frames.tryEmit(frame(50.0 + it)) }
        runCurrent()

        assertEquals(3, measurementDao.geschrieben.size)
    }

    @Test
    fun weightingWirdNurBeiBestaetigterBewertungGespeichert() = runTest(UnconfinedTestDispatcher()) {
        val recorder = MeasurementRecorder(
            zustaende, frames, sessionDao, measurementDao, connectionEventDao,
            scope = backgroundScope, now = uhr, flushInterval = Duration.ofMinutes(10), flushBatchSize = 2,
        )
        recorder.start(device)

        frames.tryEmit(frame(55.0, bewertungBestaetigt = false))
        frames.tryEmit(frame(56.0, bewertungBestaetigt = true))
        runCurrent()

        val unbestaetigt = measurementDao.geschrieben.first { it.levelDb == 55.0 }
        val bestaetigt = measurementDao.geschrieben.first { it.levelDb == 56.0 }
        assertNull("Unbestaetigte Bewertung darf nicht als Tatsache gespeichert werden", unbestaetigt.weighting)
        assertEquals("A", bestaetigt.weighting)
    }

    @Test
    fun ersteStreamingMeldungErzeugtConnectedEreignis() = runTest(UnconfinedTestDispatcher()) {
        val recorder = MeasurementRecorder(
            zustaende, frames, sessionDao, measurementDao, connectionEventDao,
            scope = backgroundScope, now = uhr,
        )
        recorder.start(device)

        zustaende.value = ConnectionState.STREAMING
        runCurrent()

        assertEquals(listOf(ConnectionEventType.CONNECTED), connectionEventDao.geschrieben.map { it.type })
    }

    @Test
    fun ausfallGefolgtVonRueckkehrErzeugtDisconnectedUndRecovered() = runTest(UnconfinedTestDispatcher()) {
        val recorder = MeasurementRecorder(
            zustaende, frames, sessionDao, measurementDao, connectionEventDao,
            scope = backgroundScope, now = uhr,
        )
        recorder.start(device)

        zustaende.value = ConnectionState.STREAMING
        zustaende.value = ConnectionState.DISCONNECTED
        zustaende.value = ConnectionState.RECONNECTING
        zustaende.value = ConnectionState.STREAMING
        runCurrent()

        assertEquals(
            listOf(ConnectionEventType.CONNECTED, ConnectionEventType.DISCONNECTED, ConnectionEventType.RECOVERED),
            connectionEventDao.geschrieben.map { it.type },
        )
    }

    @Test
    fun degradedErzeugtEigenesEreignis() = runTest(UnconfinedTestDispatcher()) {
        val recorder = MeasurementRecorder(
            zustaende, frames, sessionDao, measurementDao, connectionEventDao,
            scope = backgroundScope, now = uhr,
        )
        recorder.start(device)

        zustaende.value = ConnectionState.STREAMING
        zustaende.value = ConnectionState.DEGRADED
        runCurrent()

        assertEquals(
            listOf(ConnectionEventType.CONNECTED, ConnectionEventType.DEGRADED),
            connectionEventDao.geschrieben.map { it.type },
        )
    }

    @Test
    fun zwischenzustaendeErzeugenKeinEigenesEreignis() = runTest(UnconfinedTestDispatcher()) {
        val recorder = MeasurementRecorder(
            zustaende, frames, sessionDao, measurementDao, connectionEventDao,
            scope = backgroundScope, now = uhr,
        )
        recorder.start(device)

        zustaende.value = ConnectionState.SCANNING
        zustaende.value = ConnectionState.CONNECTING
        zustaende.value = ConnectionState.DISCOVERING
        zustaende.value = ConnectionState.SUBSCRIBING
        runCurrent()

        assertTrue(connectionEventDao.geschrieben.isEmpty())
    }

    @Test
    fun stopBeendetDieSessionUndSchreibtDenRestSofortWeg() = runTest(UnconfinedTestDispatcher()) {
        val recorder = MeasurementRecorder(
            zustaende, frames, sessionDao, measurementDao, connectionEventDao,
            scope = backgroundScope, now = uhr, flushInterval = Duration.ofMinutes(10),
        )
        recorder.start(device)
        frames.tryEmit(frame(55.0))
        runCurrent()

        recorder.stop()
        runCurrent()

        assertEquals(1, measurementDao.geschrieben.size)
        val session = sessionDao.zeilen.values.single()
        assertNotNull("Session muss nach stop() beendet sein", session.endedAt)
    }

    @Test
    fun neueSessionNachStopUndErneutemStartIstEineEigeneZeile() = runTest(UnconfinedTestDispatcher()) {
        val recorder = MeasurementRecorder(
            zustaende, frames, sessionDao, measurementDao, connectionEventDao,
            scope = backgroundScope, now = uhr,
        )
        recorder.start(device)
        recorder.stop()
        runCurrent()

        uhr.vor(Duration.ofMinutes(1))
        recorder.start(device)
        runCurrent()

        assertEquals(2, sessionDao.zeilen.size)
        assertNotNull(sessionDao.zeilen[1]!!.endedAt)
        assertNull(sessionDao.zeilen[2]!!.endedAt)
    }

    @Test
    fun zweiterStartWaehrendLaufenderSessionIstEinNoOp() = runTest(UnconfinedTestDispatcher()) {
        val recorder = MeasurementRecorder(
            zustaende, frames, sessionDao, measurementDao, connectionEventDao,
            scope = backgroundScope, now = uhr,
        )
        recorder.start(device)
        recorder.start(device)
        runCurrent()

        assertEquals(1, sessionDao.zeilen.size)
    }

    @Test
    fun holdMaxFlagWirdAlsBitVermerkt() = runTest(UnconfinedTestDispatcher()) {
        val recorder = MeasurementRecorder(
            zustaende, frames, sessionDao, measurementDao, connectionEventDao,
            scope = backgroundScope, now = uhr, flushInterval = Duration.ofMinutes(10), flushBatchSize = 1,
        )
        recorder.start(device)

        frames.tryEmit(
            MeterFrame(
                level = 55.0, weighting = null, timeWeighting = null, range = null,
                holdMax = true, holdMin = false, receivedAt = uhr.now(),
            )
        )
        runCurrent()

        val eintrag = measurementDao.geschrieben.single()
        assertEquals(MeasurementFlags.HOLD_MAX, eintrag.flags and MeasurementFlags.HOLD_MAX)
        assertEquals(0, eintrag.flags and MeasurementFlags.HOLD_MIN)
    }
}
