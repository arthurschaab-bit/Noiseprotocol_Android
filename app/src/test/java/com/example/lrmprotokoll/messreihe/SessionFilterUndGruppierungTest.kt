package com.example.lrmprotokoll.messreihe

import com.example.lrmprotokoll.data.NoiseRecord
import com.example.lrmprotokoll.data.SessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner-Feature-Auftrag 12.09.2026: Protokollreiter nach Tagen gruppiert und nach denselben
 * Kriterien wie die Startseite filterbar (dort auf Sessions statt einzelner Ereignisse
 * angewendet).
 */
class SessionFilterUndGruppierungTest {

    private fun sampleSession(
        id: Long,
        startedAt: Long = 1_700_000_000_000L,
        endedAt: Long? = 1_700_000_010_000L,
        deviceAddress: String = "AA:BB:CC:DD:EE:FF",
    ) = SessionEntity(
        id = id,
        startedAt = startedAt,
        endedAt = endedAt,
        deviceAddress = deviceAddress,
        deviceName = "PCE-323",
        weighting = "A",
        timeWeighting = "F",
    )

    private fun sampleRecord(
        id: Long,
        dbValue: Double = 50.0,
        calibratedDbA: Double? = null,
        label: String? = null,
        detectedLabel: String? = null,
        isQuietHour: Boolean = false,
        favorite: Boolean = false,
    ) = NoiseRecord(
        id = id,
        timestamp = 1_700_000_000_000L,
        amplitude = 1000.0,
        dbValue = dbValue,
        filePath = "/path/$id.wav",
        label = label,
        detectedLabel = detectedLabel,
        calibratedDbA = calibratedDbA,
        isQuietHour = isQuietHour,
        favorite = favorite,
    )

    // -------------------------------------------------------------------- sessionPasstFilter

    @Test
    fun inaktiverFilterLaesstJedeSessionDurch() {
        val session = sampleSession(1)
        val filter = SessionFilterState()
        assertFalse(filter.istAktiv)
        assertTrue(sessionPasstFilter(session, emptyList(), filter))
    }

    @Test
    fun onlyMeterPruefteDieSessionSelbstOhneEreignisse() {
        val mitGeraet = sampleSession(1, deviceAddress = "AA:BB:CC:DD:EE:FF")
        val nurMikrofon = sampleSession(2, deviceAddress = "")
        val filter = SessionFilterState(onlyMeter = true)

        assertTrue(sessionPasstFilter(mitGeraet, emptyList(), filter))
        assertFalse(sessionPasstFilter(nurMikrofon, emptyList(), filter))
    }

    @Test
    fun ereignisbezogeneKriterienBrauchenMindestensEinPassendesEreignis() {
        val session = sampleSession(1)
        val nichtPassend = sampleRecord(1, favorite = false)
        val passend = sampleRecord(2, favorite = true)
        val filter = SessionFilterState(onlyFavorites = true)

        assertFalse(sessionPasstFilter(session, listOf(nichtPassend), filter))
        assertTrue(sessionPasstFilter(session, listOf(nichtPassend, passend), filter))
    }

    @Test
    fun pegelFilterGreiftAufKalibriertenOderUnkalibriertenWertZu() {
        val session = sampleSession(1)
        val filter = SessionFilterState(minDb = 60.0f, maxDb = 80.0f)

        assertFalse(sessionPasstFilter(session, listOf(sampleRecord(1, dbValue = 40.0)), filter))
        assertTrue(sessionPasstFilter(session, listOf(sampleRecord(2, dbValue = 50.0, calibratedDbA = 65.0)), filter))
    }

    @Test
    fun labelFilterFindetSowohlManuellesAlsAuchErkanntesLabel() {
        val session = sampleSession(1)
        val filter = SessionFilterState(labelQuery = "bohr")

        assertTrue(sessionPasstFilter(session, listOf(sampleRecord(1, label = "Bohrmaschine")), filter))
        assertTrue(sessionPasstFilter(session, listOf(sampleRecord(2, detectedLabel = "Bohrhammer")), filter))
        assertFalse(sessionPasstFilter(session, listOf(sampleRecord(3, label = "Verkehr")), filter))
    }

    @Test
    fun onlyMeterUndEreignisFilterMuessenBeideZutreffen() {
        val nurMikrofon = sampleSession(1, deviceAddress = "")
        val filter = SessionFilterState(onlyMeter = true, onlyFavorites = true)

        // onlyMeter allein scheitert schon an der Session - das passende Ereignis darf das
        // nicht ueberstimmen.
        assertFalse(sessionPasstFilter(nurMikrofon, listOf(sampleRecord(1, favorite = true)), filter))
    }

    // ----------------------------------------------------------------- gruppiereSessionsNachTag

    @Test
    fun gruppiertSessionsNachKalendertag() {
        val tag1 = sampleSession(1, startedAt = 1_700_000_000_000L)
        val tag1b = sampleSession(2, startedAt = 1_700_000_500_000L)
        val tag2 = sampleSession(3, startedAt = 1_700_100_000_000L)

        val gruppen = gruppiereSessionsNachTag(listOf(tag1, tag1b, tag2), locale = java.util.Locale.GERMANY)

        assertEquals(2, gruppen.size)
        assertEquals(2, gruppen.values.first().size)
    }
}
