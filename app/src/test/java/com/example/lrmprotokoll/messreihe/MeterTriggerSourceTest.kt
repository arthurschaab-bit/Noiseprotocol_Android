package com.example.lrmprotokoll.messreihe

import com.example.lrmprotokoll.meter.MeterFrame
import com.example.lrmprotokoll.meter.Weighting
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeterTriggerSourceTest {

    private fun frame(level: Double, weighting: Weighting? = Weighting.A, bestaetigt: Boolean = false) =
        MeterFrame(
            level = level, weighting = weighting, timeWeighting = null, range = null,
            holdMax = false, holdMin = false, receivedAt = Instant.parse("2026-08-19T09:00:00Z"),
            modeAssumptionConfirmed = bestaetigt,
        )

    @Test
    fun ohneMessgeraetFaelltAufMikrofonZurueck() {
        val ergebnis = MeterTriggerSource.auswerten(
            letzterMeterFrame = null, mikrofonDb = 65.0, activeSchwelle = 60f,
        )
        assertTrue(ergebnis.ausgeloest)
        assertEquals(65.0, ergebnis.pegel, 0.0001)
        assertFalse(ergebnis.meterConnected)
        assertNull(ergebnis.calibratedDbA)
    }

    @Test
    fun ohneMessgeraetUnterhalbDerMikrofonSchwelleLoestNichtAus() {
        val ergebnis = MeterTriggerSource.auswerten(
            letzterMeterFrame = null, mikrofonDb = 10.0, activeSchwelle = 60f,
        )
        assertFalse(ergebnis.ausgeloest)
    }

    @Test
    fun mitMessgeraetLoestUeberSchwelleAus() {
        val ergebnis = MeterTriggerSource.auswerten(
            letzterMeterFrame = frame(70.0), mikrofonDb = 0.0, activeSchwelle = 60f,
        )
        assertTrue(ergebnis.ausgeloest)
        assertEquals(70.0, ergebnis.pegel, 0.0001)
        assertTrue(ergebnis.meterConnected)
        assertEquals(70.0, ergebnis.calibratedDbA)
    }

    @Test
    fun mitMessgeraetUnterSchwelleLoestNichtAus() {
        val ergebnis = MeterTriggerSource.auswerten(
            letzterMeterFrame = frame(40.0), mikrofonDb = 0.0, activeSchwelle = 60f,
        )
        assertFalse(ergebnis.ausgeloest)
    }

    @Test
    fun triggerQuellePce323IgnoriertMikrofon() {
        val ergebnis = MeterTriggerSource.auswerten(
            letzterMeterFrame = null, mikrofonDb = 85.0, activeSchwelle = 60f, triggerQuelle = "PCE_323"
        )
        assertFalse("Wenn nur PCE-323 ausgewählt ist, darf Mikrofon nicht auslösen", ergebnis.ausgeloest)
    }

    @Test
    fun triggerQuelleMikrofonIgnoriertMessgeraet() {
        val ergebnis = MeterTriggerSource.auswerten(
            letzterMeterFrame = frame(85.0), mikrofonDb = 40.0, activeSchwelle = 60f, triggerQuelle = "MIKROFON"
        )
        assertFalse("Wenn nur Mikrofon gewählt ist und unter Schwelle liegt, löst es nicht aus", ergebnis.ausgeloest)
    }

    @Test
    fun weightingWirdNurBeiBestaetigterBewertungDurchgereicht() {
        val unbestaetigt = MeterTriggerSource.auswerten(
            letzterMeterFrame = frame(70.0, bestaetigt = false), mikrofonDb = 0.0, activeSchwelle = 60f,
        )
        val bestaetigt = MeterTriggerSource.auswerten(
            letzterMeterFrame = frame(70.0, bestaetigt = true), mikrofonDb = 0.0, activeSchwelle = 60f,
        )
        assertNull("Unbestaetigte Bewertung darf nicht als Tatsache durchgereicht werden", unbestaetigt.meterWeighting)
        assertEquals("A", bestaetigt.meterWeighting)
    }

    @Test
    fun fehlendeWeightingBleibtNullAuchBeiBestaetigterMessung() {
        val ergebnis = MeterTriggerSource.auswerten(
            letzterMeterFrame = frame(70.0, weighting = null, bestaetigt = true),
            mikrofonDb = 0.0, activeSchwelle = 60f,
        )
        assertNull(ergebnis.meterWeighting)
    }
}
