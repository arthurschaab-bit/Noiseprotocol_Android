package com.example.lrmprotokoll.diagnose

import com.example.lrmprotokoll.alert.TestUhr
import com.example.lrmprotokoll.data.DiagnosticLogDao
import com.example.lrmprotokoll.data.DiagnosticLogEntity
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticLogCleanupCoordinatorTest {

    private class FakeDiagnosticLogDao : DiagnosticLogDao {
        val zeilen = mutableListOf<DiagnosticLogEntity>()
        override suspend fun insert(eintrag: DiagnosticLogEntity) { zeilen += eintrag }
        override suspend fun alle() = zeilen.sortedByDescending { it.timestamp }
        override suspend fun loescheAelterAls(grenze: Long) { zeilen.removeAll { it.timestamp < grenze } }
    }

    private val dao = FakeDiagnosticLogDao()
    private val uhr = TestUhr(Instant.parse("2026-08-19T12:00:00Z"))

    private fun eintrag(alterTage: Long, nachricht: String) = DiagnosticLogEntity(
        timestamp = uhr.now().minus(Duration.ofDays(alterTage)).toEpochMilli(),
        message = nachricht,
    )

    private fun koordinator() = DiagnosticLogCleanupCoordinator(dao, uhr, aufbewahrungstage = 7)

    @Test
    fun eintraegeInnerhalbDerFristBleibenUnangetastet() = runTest {
        dao.zeilen += eintrag(alterTage = 3, nachricht = "innerhalb")

        koordinator().bereinige()

        assertEquals(1, dao.zeilen.size)
    }

    @Test
    fun eintraegeAelterAlsSiebenTageWerdenGeloescht() = runTest {
        dao.zeilen += eintrag(alterTage = 8, nachricht = "zu alt")
        dao.zeilen += eintrag(alterTage = 1, nachricht = "frisch")

        koordinator().bereinige()

        assertEquals(1, dao.zeilen.size)
        assertEquals("frisch", dao.zeilen.single().message)
    }

    @Test
    fun grenzfallGenauSiebenTageAltWirdNichtGeloescht() = runTest {
        // Gegentest zur Grenze: >7 Tage loeschen, GENAU 7 Tage (noch) nicht - dieselbe
        // "< grenze statt <= grenze"-Unterscheidung wie beim M4-Retention-Job.
        dao.zeilen += eintrag(alterTage = 7, nachricht = "grenzwertig")

        koordinator().bereinige()

        assertEquals(1, dao.zeilen.size)
    }
}
