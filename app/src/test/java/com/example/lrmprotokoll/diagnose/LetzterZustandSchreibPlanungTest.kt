package com.example.lrmprotokoll.diagnose

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LetzterZustandSchreibPlanungTest {
    @Test
    fun stromUndBleWechselWerdenAufEinenHintergrundSchreibvorgangReduziert() =
        runTest {
            val schreibungen = mutableListOf<Boolean?>()
            val planung = LetzterZustandSchreibPlanung(this, { schreibungen += it })

            planung.aktualisieren()
            advanceTimeBy(500L)
            planung.aktualisieren()
            advanceTimeBy(999L)
            runCurrent()
            assertEquals(emptyList<Boolean?>(), schreibungen)

            advanceTimeBy(1L)
            runCurrent()
            assertEquals(listOf(null), schreibungen)
        }

    @Test
    fun ausdruecklicherStoppSchreibtSofortUndVerwirftVorgemerktenWechsel() =
        runTest {
            val schreibungen = mutableListOf<Boolean?>()
            val planung = LetzterZustandSchreibPlanung(this, { schreibungen += it })

            planung.aktualisieren()
            planung.aktualisieren(sauberBeendet = true)
            assertEquals(listOf(true), schreibungen)
            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals(listOf(true), schreibungen)
        }
}
