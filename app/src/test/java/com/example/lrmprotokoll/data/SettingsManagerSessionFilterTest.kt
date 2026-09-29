package com.example.lrmprotokoll.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S-5/F-18: Der Filter des Protokollreiters hing bis zum 29.09.2026 an einem `remember()` und war
 * nach jedem Verlassen des Reiters weg, waehrend der Filter der Startseite seit F2 persistiert.
 * Diese Klasse prueft die beiden Dinge, die daran schiefgehen koennen: dass die Voreinstellung
 * einen *inaktiven* Filter ergibt (sonst zeigte der Protokollreiter beim ersten Oeffnen eine
 * gefilterte Liste, ohne dass jemand etwas eingestellt haette) und dass eigene Schluessel benutzt
 * werden, die den Startseiten-Filter nicht mitziehen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsManagerSessionFilterTest {
    private fun settings() = SettingsManager(ApplicationProvider.getApplicationContext())

    @Test
    fun voreinstellungErgibtEinenInaktivenFilter() {
        val s = settings()
        assertEquals(0.0f, s.sessionFilterDbMin, 0.0f)
        assertEquals(120.0f, s.sessionFilterDbMax, 0.0f)
        assertEquals("", s.sessionFilterLabelQuery)
        assertFalse(s.sessionFilterOnlyMeter)
        assertFalse(s.sessionFilterOnlyCalibrated)
        assertFalse(s.sessionFilterOnlyFavorites)
        assertFalse(s.sessionFilterOnlyQuietHours)
        assertFalse(s.sessionFilterOnlyWithEvents)
    }

    @Test
    fun jederWertUeberlebtDenNeuaufbauDesManagers() {
        settings().apply {
            sessionFilterDbMin = 45.0f
            sessionFilterDbMax = 95.0f
            sessionFilterLabelQuery = "Bohren"
            sessionFilterOnlyMeter = true
            sessionFilterOnlyCalibrated = true
            sessionFilterOnlyFavorites = true
            sessionFilterOnlyQuietHours = true
            sessionFilterOnlyWithEvents = true
        }

        val neu = settings()
        assertEquals(45.0f, neu.sessionFilterDbMin, 0.0f)
        assertEquals(95.0f, neu.sessionFilterDbMax, 0.0f)
        assertEquals("Bohren", neu.sessionFilterLabelQuery)
        assertEquals(true, neu.sessionFilterOnlyMeter)
        assertEquals(true, neu.sessionFilterOnlyCalibrated)
        assertEquals(true, neu.sessionFilterOnlyFavorites)
        assertEquals(true, neu.sessionFilterOnlyQuietHours)
        assertEquals(true, neu.sessionFilterOnlyWithEvents)
    }

    /**
     * Die Schluessel sind bewusst getrennt: der Startseitenfilter arbeitet auf Einzelaufnahmen,
     * der Protokollfilter auf Sessions. Ein gemeinsamer Pegelbereich wuerde beim Wechsel des
     * Reiters still mitfiltern. Faellt dieser Test, wurde ein Schluessel doppelt vergeben.
     */
    @Test
    fun derFilterDerStartseiteBleibtUnberuehrt() {
        val s = settings()
        s.filterDbMin = 10.0f
        s.filterOnlyFavorites = true

        s.sessionFilterDbMin = 60.0f
        s.sessionFilterOnlyFavorites = false

        val neu = settings()
        assertEquals(10.0f, neu.filterDbMin, 0.0f)
        assertEquals(true, neu.filterOnlyFavorites)
        assertEquals(60.0f, neu.sessionFilterDbMin, 0.0f)
        assertFalse(neu.sessionFilterOnlyFavorites)
    }
}
