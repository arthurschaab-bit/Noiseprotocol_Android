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
import kotlinx.coroutines.test.TestScope
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
        override fun byIdFlow(id: Long) = throw NotImplementedError("im Test nicht benoetigt")
        override suspend fun alleOffenen(): List<SessionEntity> = zeilen.values.filter { it.endedAt == null }.sortedBy { it.startedAt }
        override suspend fun offeneSession(): SessionEntity? = zeilen.values.firstOrNull { it.endedAt == null }
        override fun offeneSessionFlow() = throw NotImplementedError("im Test nicht benoetigt")
        override suspend fun letzte(): SessionEntity? = zeilen.values.maxByOrNull { it.startedAt }
        override fun letzteSessionFlow() = throw NotImplementedError("im Test nicht benoetigt")
        override fun alle() = throw NotImplementedError("im Test nicht benoetigt")
        override suspend fun zwischen(von: Long, bis: Long) = zeilen.values.filter {
            it.startedAt < bis && (it.endedAt == null || it.endedAt >= von)
        }
    }

    private class FakeMeasurementDao : MeasurementDao {
        val geschrieben = mutableListOf<MeasurementEntity>()
        override suspend fun insertAll(messwerte: List<MeasurementEntity>) { geschrieben += messwerte }
        override suspend fun fuerSession(sessionId: Long) = geschrieben.filter { it.sessionId == sessionId }
        override fun fuerSessionFlow(sessionId: Long) = throw NotImplementedError("im Test nicht benoetigt")
        override fun fuerSessionAbFlow(sessionId: Long, ab: Long) = throw NotImplementedError("im Test nicht benoetigt")
        override suspend fun zwischen(von: Long, bis: Long) = geschrieben.filter { it.timestamp in von until bis }
        override suspend fun aelterAls(grenze: Long) = geschrieben.filter { it.timestamp < grenze }
        override suspend fun loescheAelterAls(grenze: Long) { geschrieben.removeAll { it.timestamp < grenze } }
        override suspend fun anzahl(): Int = geschrieben.size
    }

    private class FakeConnectionEventDao : ConnectionEventDao {
        val geschrieben = mutableListOf<ConnectionEventEntity>()
        override suspend fun insert(event: ConnectionEventEntity) { geschrieben += event }
        override suspend fun fuerSession(sessionId: Long) = geschrieben.filter { it.sessionId == sessionId }
        override fun fuerSessionFlow(sessionId: Long) = throw NotImplementedError("im Test nicht benoetigt")
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

    private fun vollerFrame(level: Double) = MeterFrame(
        level = level,
        weighting = com.example.lrmprotokoll.meter.Weighting.A,
        timeWeighting = com.example.lrmprotokoll.meter.TimeWeighting.FAST,
        range = com.example.lrmprotokoll.meter.MeasurementRange.RANGE_30_130,
        holdMax = false, holdMin = false, receivedAt = uhr.now(), modeAssumptionConfirmed = true,
    )

    @Test
    fun eineVerbindungDieNieZustandeKommtErzeugtKeineSession() = runTest(UnconfinedTestDispatcher()) {
        // Der Kern des am Geraet gemeldeten Fehlers: Ist das gepinnte Messgeraet nicht in
        // Reichweite, laeuft der Supervisor in eine Reconnect-Schleife. Frueher fuellte sich das
        // Protokoll dabei mit Sitzungen "PCE-323" ohne einen einzigen Messwert.
        val recorder = MeasurementRecorder(
            zustaende, frames, sessionDao, measurementDao, connectionEventDao,
            scope = backgroundScope, now = uhr, flushInterval = Duration.ofSeconds(5),
        )
        recorder.start(device)
        zustaende.value = ConnectionState.CONNECTING
        zustaende.value = ConnectionState.DISCONNECTED
        zustaende.value = ConnectionState.RECONNECTING
        zustaende.value = ConnectionState.FAILED
        runCurrent()

        assertTrue("Ohne einen einzigen Datenpunkt gibt es keinen Messvorgang", sessionDao.zeilen.isEmpty())
        assertTrue("Und auch keine Verbindungsereignisse ohne Session", connectionEventDao.geschrieben.isEmpty())
    }

    @Test
    fun derErsteFliessendeDatenstromEroeffnetDieSession() = runTest(UnconfinedTestDispatcher()) {
        val recorder = MeasurementRecorder(
            zustaende, frames, sessionDao, measurementDao, connectionEventDao,
            scope = backgroundScope, now = uhr, flushInterval = Duration.ofSeconds(5),
        )
        recorder.start(device)
        zustaende.value = ConnectionState.STREAMING
        runCurrent()

        assertEquals(1, sessionDao.zeilen.size)
        val session = sessionDao.zeilen.values.single()
        assertEquals("AA:BB:CC:DD:EE:FF", session.deviceAddress)
        assertNull("Noch nicht beendet", session.endedAt)
        assertNull("Weighting unbestaetigt -> nicht gespeichert", session.weighting)
    }

    @Test
    fun einReconnectEroeffnetKeineZweiteSession() = runTest(UnconfinedTestDispatcher()) {
        // Eine Ausfallperiode innerhalb einer Messung ist ein Ereignis, kein neuer Messvorgang.
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

        assertEquals(1, sessionDao.zeilen.size)
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
    fun zeitbewertungUndBereichWerdenNurBeiBestaetigterAnnahmeGespeichert() = runTest(UnconfinedTestDispatcher()) {
        val recorder = MeasurementRecorder(
            zustaende, frames, sessionDao, measurementDao, connectionEventDao,
            scope = backgroundScope, now = uhr, flushInterval = Duration.ofMinutes(10), flushBatchSize = 1,
        )
        recorder.start(device)

        frames.tryEmit(vollerFrame(55.0))
        runCurrent()

        val eintrag = measurementDao.geschrieben.single()
        assertEquals("FAST", eintrag.timeWeighting)
        assertEquals("RANGE_30_130", eintrag.range)
    }

    @Test
    fun stopSchreibtDenLetztenBekanntenFrameKontextInDieSession() = runTest(UnconfinedTestDispatcher()) {
        val recorder = MeasurementRecorder(
            zustaende, frames, sessionDao, measurementDao, connectionEventDao,
            scope = backgroundScope, now = uhr, flushInterval = Duration.ofMinutes(10), flushBatchSize = 1,
        )
        recorder.start(device)

        frames.tryEmit(vollerFrame(55.0))
        runCurrent()
        recorder.stop()
        runCurrent()

        val session = sessionDao.zeilen.values.single()
        assertEquals("A", session.weighting)
        assertEquals("FAST", session.timeWeighting)
        assertEquals("RANGE_30_130", session.range)
    }

    @Test
    fun stopBehaeltDenLetztenBekanntenWertAuchWennDerAllerletzteFrameUnbestaetigtWar() = runTest(UnconfinedTestDispatcher()) {
        // Ein Session-Kontext, der bei jedem unbestaetigten Frame auf null zurueckfaellt, waere
        // schlechter als gar keine Aktualisierung - siehe SessionEntity-KDoc "zuletzt bekannter
        // Wert". Der letzte GESICHERTE Wert muss ueberleben, nicht der allerletzte Frame roh.
        val recorder = MeasurementRecorder(
            zustaende, frames, sessionDao, measurementDao, connectionEventDao,
            scope = backgroundScope, now = uhr, flushInterval = Duration.ofMinutes(10), flushBatchSize = 2,
        )
        recorder.start(device)

        frames.tryEmit(vollerFrame(55.0))
        frames.tryEmit(frame(56.0, bewertungBestaetigt = false))
        runCurrent()
        recorder.stop()
        runCurrent()

        val session = sessionDao.zeilen.values.single()
        assertEquals("A", session.weighting)
        assertEquals("FAST", session.timeWeighting)
        assertEquals("RANGE_30_130", session.range)
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
        zustaende.value = ConnectionState.STREAMING
        runCurrent()
        recorder.stop()
        runCurrent()

        uhr.vor(Duration.ofMinutes(1))
        zustaende.value = ConnectionState.IDLE
        recorder.start(device)
        zustaende.value = ConnectionState.STREAMING
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
        zustaende.value = ConnectionState.STREAMING
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

    // --- Verwaiste Sessions (Prozesstod zwischen start() und stop()) ---

    private fun TestScope.recorderMit(sessionDao: FakeSessionDao, measurementDao: FakeMeasurementDao) =
        MeasurementRecorder(
            zustaende, frames, sessionDao, measurementDao, connectionEventDao,
            scope = backgroundScope, now = uhr,
        )

    private fun offeneSession(id: Long = 0, startedAt: Long) = SessionEntity(
        id = id, startedAt = startedAt, endedAt = null,
        deviceAddress = "AA:BB:CC:DD:EE:FF", deviceName = "PCE-323",
        weighting = null, timeWeighting = null,
    )

    @Test
    fun startSchliesstEineVerwaisteSessionMitDemLetztenMesswertAb() = runTest(UnconfinedTestDispatcher()) {
        val sessionDao = FakeSessionDao()
        val measurementDao = FakeMeasurementDao()
        val verwaist = sessionDao.insert(offeneSession(startedAt = 1_000L))
        measurementDao.insertAll(
            listOf(
                MeasurementEntity(sessionId = verwaist, timestamp = 5_000L, levelDb = 60.0, weighting = null, flags = 0),
                MeasurementEntity(sessionId = verwaist, timestamp = 9_000L, levelDb = 61.0, weighting = null, flags = 0),
            )
        )

        recorderMit(sessionDao, measurementDao).start(device)
        runCurrent()

        assertEquals(
            "Endzeitpunkt muss der letzte belegte Messwert sein, nicht die aktuelle Uhrzeit",
            9_000L,
            sessionDao.zeilen[verwaist]?.endedAt,
        )
    }

    @Test
    fun verwaisteSessionOhneMesswerteBekommtIhreStartzeitAlsEnde() = runTest(UnconfinedTestDispatcher()) {
        // Eine Session der Laenge null ist die ehrliche Aussage "begonnen, nie etwas
        // aufgezeichnet" - eine erfundene Mindestdauer waere eine erfundene Messzeit.
        val sessionDao = FakeSessionDao()
        val verwaist = sessionDao.insert(offeneSession(startedAt = 7_777L))

        recorderMit(sessionDao, FakeMeasurementDao()).start(device)
        runCurrent()

        assertEquals(7_777L, sessionDao.zeilen[verwaist]?.endedAt)
    }

    @Test
    fun mehrereVerwaisteSessionsWerdenAlleGeschlossen() = runTest(UnconfinedTestDispatcher()) {
        val sessionDao = FakeSessionDao()
        val ids = (1..3).map { sessionDao.insert(offeneSession(startedAt = it * 100L)) }

        recorderMit(sessionDao, FakeMeasurementDao()).start(device)
        runCurrent()

        ids.forEach { id ->
            assertNotNull("Session $id muss geschlossen worden sein", sessionDao.zeilen[id]?.endedAt)
        }
    }

    @Test
    fun dieNeueSessionBleibtOffen() = runTest(UnconfinedTestDispatcher()) {
        // Gegenprobe: Das Aufraeumen darf nicht die gerade eroeffnete Session mit erwischen -
        // sonst waere jede laufende Messung sofort als beendet markiert.
        val sessionDao = FakeSessionDao()
        sessionDao.insert(offeneSession(startedAt = 1_000L))

        val recorder = recorderMit(sessionDao, FakeMeasurementDao())
        recorder.start(device)
        zustaende.value = ConnectionState.STREAMING
        runCurrent()

        val neue = sessionDao.zeilen[recorder.laufendeSessionId]
        assertNotNull(neue)
        assertNull("Die gerade gestartete Session muss offen bleiben", neue!!.endedAt)
        assertEquals(1, sessionDao.zeilen.values.count { it.endedAt == null })
    }

    @Test
    fun bereitsGeschlosseneSessionsWerdenNichtAngefasst() = runTest(UnconfinedTestDispatcher()) {
        val sessionDao = FakeSessionDao()
        val geschlossen = sessionDao.insert(offeneSession(startedAt = 1_000L).copy(endedAt = 2_000L))

        recorderMit(sessionDao, FakeMeasurementDao()).start(device)
        runCurrent()

        assertEquals(2_000L, sessionDao.zeilen[geschlossen]?.endedAt)
    }

    // ------------------------------------------------------------------
    // Mikrofon-Session (M11/E1) - eine Messung ohne Messgeraet
    // ------------------------------------------------------------------

    @Test
    fun mikrofonMessungLegtEineSessionOhneGeraeteadresseAn() = runTest(UnconfinedTestDispatcher()) {
        val recorder = recorderMit(sessionDao, measurementDao)
        recorder.starteMikrofonMessung()
        runCurrent()

        val session = sessionDao.zeilen.values.single()
        assertEquals("", session.deviceAddress)
        assertEquals(MIKROFON_GERAETENAME, session.deviceName)
        assertNull("Ohne Messgeraet gibt es keine bestaetigte Bewertung", session.weighting)
        assertNull("Laeuft noch", session.endedAt)
    }

    @Test
    fun mikrofonPegelWirdAlsMesswertDerLaufendenSessionGeschrieben() = runTest(UnconfinedTestDispatcher()) {
        // Der Kern des Fehlers, den dieser Test absichert: Ohne Messwerte war die
        // Mikrofon-Session als juengste Session zwar die, die der Pegelverlauf auf der Startseite
        // anzeigt - nur hatte sie nichts anzuzeigen.
        val recorder = recorderMit(sessionDao, measurementDao)
        recorder.starteMikrofonMessung()
        runCurrent()

        recorder.mikrofonPegel(58.0)
        runCurrent()
        advanceTimeBy(5_100)
        runCurrent()

        val messwert = measurementDao.geschrieben.single()
        assertEquals(recorder.laufendeSessionId, messwert.sessionId)
        assertEquals(58.0, messwert.levelDb, 0.001)
    }

    @Test
    fun mikrofonMesswertBleibtOhneBewertungsangabe() = runTest(UnconfinedTestDispatcher()) {
        // Das Mikrofon ist unkalibriert - ein eingetragenes "A" waere eine gespeicherte
        // Tatsachenbehauptung, die es nicht gibt (MeasurementEntity-KDoc).
        val recorder = recorderMit(sessionDao, measurementDao)
        recorder.starteMikrofonMessung()
        runCurrent()

        recorder.mikrofonPegel(58.0)
        advanceTimeBy(5_100)
        runCurrent()

        val messwert = measurementDao.geschrieben.single()
        assertNull(messwert.weighting)
        assertNull(messwert.timeWeighting)
        assertNull(messwert.range)
        assertEquals("Hold/LargeJump sind Geraeteeigenschaften des PCE-323", 0, messwert.flags)
    }

    @Test
    fun mikrofonPegelWirdAufEinenWertJeSekundeAusgeduennt() = runTest(UnconfinedTestDispatcher()) {
        val recorder = recorderMit(sessionDao, measurementDao)
        recorder.starteMikrofonMessung()
        runCurrent()

        // Kadenz des Audiopuffers: viele Werte, ohne dass die Uhr weiterlaeuft.
        repeat(50) { recorder.mikrofonPegel(50.0 + it) }
        runCurrent()
        uhr.vor(Duration.ofSeconds(1))
        repeat(50) { recorder.mikrofonPegel(70.0) }
        runCurrent()

        advanceTimeBy(5_100)
        runCurrent()

        assertEquals(2, measurementDao.geschrieben.size)
        assertEquals(50.0, measurementDao.geschrieben[0].levelDb, 0.001)
        assertEquals(70.0, measurementDao.geschrieben[1].levelDb, 0.001)
    }

    @Test
    fun mikrofonPegelOhneLaufendeSessionWirdVerworfen() = runTest(UnconfinedTestDispatcher()) {
        val recorder = recorderMit(sessionDao, measurementDao)
        recorder.mikrofonPegel(58.0)
        advanceTimeBy(5_100)
        runCurrent()

        assertTrue(measurementDao.geschrieben.isEmpty())
    }

    @Test
    fun mikrofonPegelLandetNichtInEinerMessgeraetSession() = runTest(UnconfinedTestDispatcher()) {
        // Die kalibrierte Messreihe des Messgeraets darf keine unkalibrierten Mikrofonwerte
        // enthalten - sonst waere nicht mehr unterscheidbar, welcher Wert woher stammt.
        val recorder = recorderMit(sessionDao, measurementDao)
        recorder.start(device)
        runCurrent()

        recorder.mikrofonPegel(58.0)
        advanceTimeBy(5_100)
        runCurrent()

        assertTrue(measurementDao.geschrieben.isEmpty())
    }

    @Test
    fun messgeraetSessionBeendetDieLaufendeMikrofonSession() = runTest(UnconfinedTestDispatcher()) {
        val recorder = recorderMit(sessionDao, measurementDao)
        recorder.starteMikrofonMessung()
        runCurrent()
        val mikrofonSessionId = recorder.laufendeSessionId
        recorder.mikrofonPegel(58.0)
        runCurrent()

        uhr.vor(Duration.ofSeconds(30))
        recorder.start(device)
        zustaende.value = ConnectionState.STREAMING
        runCurrent()

        assertNotNull(
            "Die Mikrofon-Session muss beim Quellenwechsel geschlossen werden",
            sessionDao.zeilen[mikrofonSessionId]?.endedAt,
        )
        assertEquals(
            "Der gepufferte Mikrofonwert darf beim Quellenwechsel nicht verlorengehen",
            listOf(mikrofonSessionId),
            measurementDao.geschrieben.map { it.sessionId },
        )
        assertEquals(2, sessionDao.zeilen.size)
    }

    @Test
    fun stopSchreibtDenRestDerMikrofonMessungWeg() = runTest(UnconfinedTestDispatcher()) {
        val recorder = recorderMit(sessionDao, measurementDao)
        recorder.starteMikrofonMessung()
        runCurrent()
        val sessionId = recorder.laufendeSessionId
        recorder.mikrofonPegel(58.0)
        runCurrent()

        recorder.stop()
        runCurrent()

        assertEquals(1, measurementDao.geschrieben.size)
        assertNotNull(sessionDao.zeilen[sessionId]?.endedAt)
    }

    @Test
    fun mikrofonUndMessgeraetLaufenNiemalsAlsZweiOffeneSessionen() = runTest(UnconfinedTestDispatcher()) {
        // Der am Geraet gemeldete Fehler: Im Protokoll standen zwei parallele Aufzeichnungen -
        // "PCE-323" und "Smartphone-Mikrofon" -, und die Mikrofon-Zeile galt dauerhaft als
        // laufend. Der AudioRecordingService ruft beide Starts unmittelbar nacheinander auf.
        val recorder = recorderMit(sessionDao, measurementDao)

        recorder.starteMikrofonMessung()
        recorder.start(device)
        zustaende.value = ConnectionState.STREAMING
        runCurrent()

        assertEquals(
            "Es darf immer nur EINE Session offen sein",
            1,
            sessionDao.zeilen.values.count { it.endedAt == null },
        )
        val offene = sessionDao.zeilen.values.single { it.endedAt == null }
        assertEquals("Und zwar die des Messgeraets", "AA:BB:CC:DD:EE:FF", offene.deviceAddress)
    }

    @Test
    fun einMikrofonlaufOhneErreichbaresMessgeraetBleibtEineEinzigeSession() = runTest(UnconfinedTestDispatcher()) {
        // Die haeufigere Haelfte desselben Fehlers: Das gepinnte Geraet antwortet nie.
        val recorder = recorderMit(sessionDao, measurementDao)

        recorder.starteMikrofonMessung()
        recorder.start(device)
        zustaende.value = ConnectionState.CONNECTING
        zustaende.value = ConnectionState.RECONNECTING
        zustaende.value = ConnectionState.FAILED
        runCurrent()

        assertEquals(1, sessionDao.zeilen.size)
        assertEquals(MIKROFON_GERAETENAME, sessionDao.zeilen.values.single().deviceName)
    }

    @Test
    fun einAusfallDesMessgeraetsErscheintNichtInDerMikrofonSession() = runTest(UnconfinedTestDispatcher()) {
        // Eine Mikrofon-Session hat kein Geraet - ein Verbindungsereignis dort waere die
        // Behauptung, es haette eines gegeben.
        val recorder = recorderMit(sessionDao, measurementDao)
        recorder.starteMikrofonMessung()
        recorder.start(device)
        runCurrent()

        zustaende.value = ConnectionState.DISCONNECTED
        runCurrent()

        assertTrue(connectionEventDao.geschrieben.isEmpty())
    }
}
