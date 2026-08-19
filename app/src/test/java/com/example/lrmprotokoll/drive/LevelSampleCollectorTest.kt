package com.example.lrmprotokoll.drive

import com.example.lrmprotokoll.data.LevelSampleDao
import com.example.lrmprotokoll.data.LevelSampleEntity
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Puffert der Collector wirklich, statt bei jedem [LevelSampleCollector.pegel]-Aufruf zu
 * schreiben? Bei ~20 Hz Mikrofon-Polling waere ein Insert pro Wert unnoetiger SQLite-Druck fuer
 * Daten, die ohnehin erst zu 30-min-Zyklen verdichtet werden (Plan 8.2-Prinzip).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LevelSampleCollectorTest {

    private class FakeDao : LevelSampleDao {
        val geschrieben = mutableListOf<LevelSampleEntity>()
        var insertAllAufrufe = 0

        override suspend fun insert(sample: LevelSampleEntity) { geschrieben += sample }
        override suspend fun insertAll(samples: List<LevelSampleEntity>) {
            insertAllAufrufe++
            geschrieben += samples
        }
        override suspend fun zwischen(von: Long, bis: Long): List<LevelSampleEntity> =
            geschrieben.filter { it.at in von until bis }
        override suspend fun loescheVor(vor: Long) { geschrieben.removeAll { it.at < vor } }
        override suspend fun anzahl(): Int = geschrieben.size
    }

    @Test
    fun schreibtNichtSofortSondernErstBeimFlush() = runTest {
        val dao = FakeDao()
        val collector = LevelSampleCollector(dao, scope = backgroundScope, flushInterval = Duration.ofSeconds(5))
        collector.start()

        collector.pegel("MIKROFON", 55.0, Instant.now())
        runCurrent()

        assertTrue("Vor dem ersten Flush darf noch nichts in der Datenbank stehen", dao.geschrieben.isEmpty())
    }

    @Test
    fun flushtPeriodischNachDemIntervall() = runTest {
        val dao = FakeDao()
        val collector = LevelSampleCollector(dao, scope = backgroundScope, flushInterval = Duration.ofSeconds(5))
        collector.start()

        collector.pegel("MIKROFON", 55.0, Instant.now())
        collector.pegel("MIKROFON", 56.0, Instant.now())
        advanceTimeBy(5_001)
        runCurrent()

        assertEquals(2, dao.geschrieben.size)
        assertEquals(1, dao.insertAllAufrufe)
    }

    @Test
    fun flushtSofortBeiErreichenDerBatchgroesse() = runTest {
        val dao = FakeDao()
        val collector = LevelSampleCollector(
            dao, scope = backgroundScope, flushInterval = Duration.ofMinutes(10), flushBatchSize = 3,
        )
        collector.start()

        repeat(3) { collector.pegel("MIKROFON", 50.0 + it, Instant.now()) }
        runCurrent()

        assertEquals(
            "Bei erreichter Batchgroesse muss sofort geschrieben werden, nicht erst nach 10 Minuten",
            3,
            dao.geschrieben.size,
        )
    }

    @Test
    fun stopSchreibtDenRestSofortWeg() = runTest {
        val dao = FakeDao()
        val collector = LevelSampleCollector(dao, scope = backgroundScope, flushInterval = Duration.ofMinutes(10))
        collector.start()

        collector.pegel("PCE_323", 60.0, Instant.now())
        collector.stop()
        runCurrent()

        assertEquals(
            "Sonst gehen die letzten Werte vor einem Dienst-Stopp verloren",
            1,
            dao.geschrieben.size,
        )
    }

    /**
     * Ohne den No-Op-Schutz ueberschreibt der zweite start() lediglich die [LevelSampleCollector]-
     * interne Jobreferenz - die erste Flush-Schleife liefe unkontrolliert weiter, weil stop()
     * nur noch die zweite (referenzierte) Schleife kennt und canceln kann. Ein Test, der einfach
     * nur die Aufrufzahl direkt nach dem zweiten start() prueft, uebersieht das: Beide Schleifen
     * laufen zu diesem Zeitpunkt noch synchron und der Puffer ist nach dem ersten Flush leer, der
     * zweite findet also ohnehin nichts zu schreiben - Zufall der Groessenordnung, kein Beweis.
     * Deshalb hier: stop() aufrufen, DANACH einen Wert einspeisen und pruefen, dass ueberhaupt
     * nichts mehr geschrieben wird.
     */
    @Test
    fun stopBeendetAuchEineDurchEinenZweitenStartGeleakteSchleife() = runTest {
        val dao = FakeDao()
        val collector = LevelSampleCollector(dao, scope = backgroundScope, flushInterval = Duration.ofSeconds(5))
        collector.start()
        collector.start()
        collector.stop() // Puffer ist hier leer, stop()s eigener Sofort-Flush schreibt nichts

        collector.pegel("MIKROFON", 55.0, Instant.now())
        advanceTimeBy(20_000)
        runCurrent()

        assertEquals(
            "Nach stop() darf keine Flush-Schleife mehr periodisch schreiben - auch nicht eine, " +
                "die durch einen zweiten start()-Aufruf ungebremst weiterlief",
            0,
            dao.insertAllAufrufe,
        )
    }

    @Test
    fun ohneWerteFindetKeinLeererFlushStatt() = runTest {
        val dao = FakeDao()
        val collector = LevelSampleCollector(dao, scope = backgroundScope, flushInterval = Duration.ofSeconds(5))
        collector.start()

        advanceTimeBy(5_001)
        runCurrent()

        assertEquals(0, dao.insertAllAufrufe)
    }
}
