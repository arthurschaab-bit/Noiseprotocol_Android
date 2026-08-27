package com.example.lrmprotokoll.data

import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PROMPT_M10_FUNKTIONEN.md F12: [SessionDao.zwischen] muss ueberlappende Sessions finden, nicht
 * nur die, deren [SessionEntity.startedAt] im Zeitraum liegt - eine Session, die kurz vor dem
 * angefragten Zeitraum begann und hineinlaeuft, muss fuer die Ausfallbaender-Ableitung eines
 * Periodenberichts trotzdem mitgezaehlt werden (siehe `ermittlePeriodenBericht` in
 * report/PeriodenBerichtDaten.kt).
 *
 * Weit auseinanderliegende Basiszeitstempel je Testmethode - dieselbe Begruendung wie in
 * [MeasurementDaoTest]: die Datenbank ist nicht in-memory-isoliert je Testmethode.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionDaoTest {

    private fun dao() =
        ApplicationProvider.getApplicationContext<LaermprotokollApp>().container.database.sessionDao()

    private fun session(startedAt: Long, endedAt: Long?) = SessionEntity(
        startedAt = startedAt,
        endedAt = endedAt,
        deviceAddress = "AA:BB:CC:DD:EE:FF",
        deviceName = "Testgerät",
        weighting = null,
        timeWeighting = null,
    )

    @Test
    fun findetSessionDieVollstaendigImZeitraumLiegt() = runBlocking {
        val dao = dao()
        val basis = 2_000_000_000_000L
        dao.insert(session(basis + 1_000, basis + 2_000))

        val gefunden = dao.zwischen(basis, basis + 10_000)

        assertTrue(gefunden.any { it.startedAt == basis + 1_000 })
    }

    @Test
    fun findetSessionDieVorDemZeitraumBeginntUndHineinlaeuft() = runBlocking {
        val dao = dao()
        val basis = 2_100_000_000_000L
        dao.insert(session(basis - 5_000, basis + 5_000))

        val gefunden = dao.zwischen(basis, basis + 10_000)

        assertTrue(gefunden.any { it.startedAt == basis - 5_000 })
    }

    @Test
    fun findetNochLaufendeSessionOhneEndedAt() = runBlocking {
        val dao = dao()
        val basis = 2_200_000_000_000L
        dao.insert(session(basis - 5_000, null))

        val gefunden = dao.zwischen(basis, basis + 10_000)

        assertTrue(gefunden.any { it.startedAt == basis - 5_000 && it.endedAt == null })
    }

    @Test
    fun ignoriertSessionDieVorDemZeitraumEndete() = runBlocking {
        val dao = dao()
        val basis = 2_300_000_000_000L
        dao.insert(session(basis - 10_000, basis - 5_000))

        val gefunden = dao.zwischen(basis, basis + 10_000)

        assertTrue(gefunden.none { it.startedAt == basis - 10_000 })
    }

    @Test
    fun ignoriertSessionDieErstNachDemZeitraumBeginnt() = runBlocking {
        val dao = dao()
        val basis = 2_400_000_000_000L
        dao.insert(session(basis + 20_000, basis + 25_000))

        val gefunden = dao.zwischen(basis, basis + 10_000)

        assertTrue(gefunden.none { it.startedAt == basis + 20_000 })
    }
}
