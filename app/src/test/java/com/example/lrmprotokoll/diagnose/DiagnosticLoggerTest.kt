package com.example.lrmprotokoll.diagnose

import com.example.lrmprotokoll.alert.TestUhr
import com.example.lrmprotokoll.data.DiagnosticLogDao
import com.example.lrmprotokoll.data.DiagnosticLogEntity
import java.time.Instant
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric, weil protokolliere() bei einem DAO-Fehler echtes android.util.Log durchlaeuft. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticLoggerTest {

    private class FakeDiagnosticLogDao : DiagnosticLogDao {
        val zeilen = mutableListOf<DiagnosticLogEntity>()
        override suspend fun insert(eintrag: DiagnosticLogEntity) { zeilen += eintrag }
        override fun alle() = flowOf(zeilen.sortedByDescending { it.timestamp })
        override suspend fun loescheAelterAls(grenze: Long) { zeilen.removeAll { it.timestamp < grenze } }
    }

    private val dao = FakeDiagnosticLogDao()
    private val uhr = TestUhr(Instant.parse("2026-08-19T12:00:00Z"))

    @Test
    fun schreibtNurWennAktiv() = runTest {
        val logger = DiagnosticLogger(dao, uhr, aktiv = { true })

        logger.protokolliere("DEGRADED: Datenstillstand")

        assertEquals(1, dao.zeilen.size)
        assertEquals("DEGRADED: Datenstillstand", dao.zeilen.single().message)
    }

    @Test
    fun schreibtNichtsWennAbgeschaltet() = runTest {
        // Plan Abschnitt 6: "standardmaessig aus" - der Default-Fall darf nie eine Zeile
        // erzeugen, ganz unabhaengig davon, wie oft protokolliere() aufgerufen wird.
        val logger = DiagnosticLogger(dao, uhr, aktiv = { false })

        logger.protokolliere("sollte nie ankommen")
        logger.protokolliere("auch nicht")

        assertTrue("Bei abgeschaltetem Log darf keine Zeile geschrieben werden", dao.zeilen.isEmpty())
    }

    @Test
    fun aktivWirdBeiJedemAufrufNeuAusgewertet() = runTest {
        // Kein einmalig gecachtes Flag: ein Umschalten in den Einstellungen muss ohne Neustart
        // der haltenden Klasse (ConnectionSupervisor) wirken.
        var eingeschaltet = false
        val logger = DiagnosticLogger(dao, uhr, aktiv = { eingeschaltet })

        logger.protokolliere("vor dem Einschalten")
        assertTrue(dao.zeilen.isEmpty())

        eingeschaltet = true
        logger.protokolliere("nach dem Einschalten")
        assertEquals(1, dao.zeilen.size)
        assertEquals("nach dem Einschalten", dao.zeilen.single().message)
    }
}
