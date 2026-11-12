package com.example.lrmprotokoll.messreihe

import com.example.lrmprotokoll.data.KalibrierEnde
import com.example.lrmprotokoll.data.KalibrierMesswertEntity
import com.example.lrmprotokoll.data.KalibrierphaseDao
import com.example.lrmprotokoll.data.KalibrierphaseEntity
import java.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessphasenSteuerungTest {
    private class FakeKalibrierphaseDao : KalibrierphaseDao {
        val phasen = linkedMapOf<Long, KalibrierphaseEntity>()
        val werte = mutableListOf<KalibrierMesswertEntity>()
        private var naechsteId = 1L

        override suspend fun insert(phase: KalibrierphaseEntity): Long {
            val id = naechsteId++
            phasen[id] = phase.copy(id = id)
            return id
        }

        override suspend fun update(phase: KalibrierphaseEntity) {
            phasen[phase.id] = phase
        }

        override suspend fun insertMesswerte(werte: List<KalibrierMesswertEntity>) {
            this.werte += werte
        }

        override suspend fun byId(id: Long) = phasen[id]

        override suspend fun zwischen(von: Long, bis: Long) =
            phasen.values.filter { it.startedAt in von until bis }
    }

    private val dao = FakeKalibrierphaseDao()
    private val pegel = MutableSharedFlow<Pair<Long, Double>>(extraBufferCapacity = 256)
    private val meldungen = mutableListOf<String>()

    private fun TestScope.steuerung(
        kalibrierung: Duration = Duration.ofMinutes(30),
        aufbau: Duration = Duration.ofMinutes(60),
    ) = MessphasenSteuerung(
        dao = dao,
        messgeraetPegel = pegel,
        scope = backgroundScope,
        jetzt = { testScheduler.currentTime },
        hoechstdauerKalibrierung = kalibrierung,
        hoechstdauerAufbau = aufbau,
        onBreadcrumb = { text, _ -> meldungen += text },
    )

    @Test
    fun ohneMessgeraetBeginntDirektDerAufbau() = runTest(UnconfinedTestDispatcher()) {
        val s = steuerung()
        s.startManuell(messgeraetErwartet = false)

        assertEquals(Messphase.AUFBAU, s.phase.value)
        assertTrue(s.erfassungPausiert.value)
        assertTrue(dao.phasen.isEmpty())
    }

    @Test
    fun vollerAblaufPausiertDieErfassungBisZumAbschlussDesAufbaus() = runTest(UnconfinedTestDispatcher()) {
        val s = steuerung()
        assertFalse(s.erfassungPausiert.value)

        s.startManuell(messgeraetErwartet = true)
        runCurrent()
        assertEquals(Messphase.KALIBRIERUNG, s.phase.value)
        assertTrue(s.erfassungPausiert.value)

        var t = 0L
        repeat(14) { pegel.emit(t to 93.6); t += 500 }
        runCurrent()
        assertEquals(-0.4, s.anzeige.value.abweichungVorherDb!!, 0.001)

        s.kalibrierungBeenden(vorher = -0.4, nachher = 0.0, korrigiert = false, grund = KalibrierEnde.BESTAETIGT)
        advanceUntilIdle()
        assertEquals(Messphase.AUFBAU, s.phase.value)
        assertTrue("Im Aufbau werden noch keine Werte erfasst", s.erfassungPausiert.value)

        s.aufbauAbschliessen()
        assertEquals(Messphase.KEINE, s.phase.value)
        assertFalse(s.erfassungPausiert.value)

        val phase = dao.phasen.values.single()
        assertEquals(KalibrierEnde.BESTAETIGT, phase.endeGrund)
        assertEquals(-0.4, phase.abweichungVorherDb!!, 0.0)
        assertEquals(0.0, phase.abweichungNachherDb!!, 0.0)
        assertEquals("Alle Pegel der Phase sind gespeichert, auch der nicht volle Puffer", 14, dao.werte.size)
    }

    @Test
    fun kalibrierungUeberspringenWirdFestgehaltenUndLiefertKeineAbweichung() = runTest(UnconfinedTestDispatcher()) {
        val s = steuerung()
        s.startManuell(true)
        runCurrent()

        s.kalibrierungUeberspringen()
        advanceUntilIdle()

        val phase = dao.phasen.values.single()
        assertEquals(KalibrierEnde.UEBERSPRUNGEN, phase.endeGrund)
        assertNull(phase.abweichungVorherDb)
        assertEquals(Messphase.AUFBAU, s.phase.value)
    }

    @Test
    fun einzweiterStartWaehrendEinerPhaseWirdIgnoriert() = runTest(UnconfinedTestDispatcher()) {
        val s = steuerung()
        s.startManuell(true)
        runCurrent()
        s.startManuell(true)
        runCurrent()

        assertEquals(1, dao.phasen.size)
    }

    @Test
    fun vergesseneKalibrierungEndetNachDerHoechstdauerUndDieMessungLaeuftWeiter() = runTest(UnconfinedTestDispatcher()) {
        val s = steuerung(kalibrierung = Duration.ofMinutes(30))
        s.startManuell(true)
        runCurrent()

        advanceTimeBy(30 * 60_000L + 1_000)
        advanceUntilIdle()

        assertEquals(Messphase.KEINE, s.phase.value)
        assertFalse(s.erfassungPausiert.value)
        assertEquals(KalibrierEnde.ZEITUEBERSCHREITUNG, dao.phasen.values.single().endeGrund)
    }

    @Test
    fun vergessenerAufbauEndetNachDerHoechstdauer() = runTest(UnconfinedTestDispatcher()) {
        val s = steuerung(aufbau = Duration.ofMinutes(60))
        s.startManuell(false)

        advanceTimeBy(60 * 60_000L + 1_000)
        advanceUntilIdle()

        assertEquals(Messphase.KEINE, s.phase.value)
        assertFalse(s.erfassungPausiert.value)
    }

    @Test
    fun aufbauAbschliessenOhneLaufendeAufbauphaseIstEinNoOp() = runTest(UnconfinedTestDispatcher()) {
        val s = steuerung()
        s.aufbauAbschliessen()
        s.kalibrierungBeenden(null, null, false, KalibrierEnde.MANUELL)

        assertEquals(Messphase.KEINE, s.phase.value)
        assertTrue(dao.phasen.isEmpty())
    }
}
