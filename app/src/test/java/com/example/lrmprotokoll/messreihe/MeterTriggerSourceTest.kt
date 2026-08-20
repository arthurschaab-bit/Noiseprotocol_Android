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
            letzterMeterFrame = null, mikrofonDb = 65.0, mikrofonSchwelle = 60f, meterSchwelle = 55f,
        )
        assertTrue(ergebnis.ausgeloest)
        assertEquals(65.0, ergebnis.pegel, 0.0001)
        assertFalse(ergebnis.meterConnected)
        assertNull(ergebnis.calibratedDbA)
    }

    @Test
    fun mitMessgeraetGiltDessenPegelUndDessenSchwelle() {
        // Mikrofonwert liegt UNTER dessen Schwelle, Messgeraet-Wert liegt UEBER seiner eigenen -
        // nur der Messgeraet-Pfad darf ausloesen.
        val ergebnis = MeterTriggerSource.auswerten(
            letzterMeterFrame = frame(70.0), mikrofonDb = 10.0, mikrofonSchwelle = 60f, meterSchwelle = 65f,
        )
        assertTrue(ergebnis.ausgeloest)
        assertEquals(70.0, ergebnis.pegel, 0.0001)
        assertTrue(ergebnis.meterConnected)
        assertEquals(70.0, ergebnis.calibratedDbA)
    }

    @Test
    fun mikrofonSchwelleWirdBeiVerbundenemMessgeraetNichtMehrGeprueft() {
        // Gegenprobe zum vorigen Test: Mikrofonwert liegt WEIT ueber seiner Schwelle, aber das
        // Messgeraet liegt unter seiner - es darf trotzdem NICHT ausloesen, weil bei
        // verbundenem Messgeraet die Mikrofon-Schwelle irrelevant ist (Plan 4.5).
        val ergebnis = MeterTriggerSource.auswerten(
            letzterMeterFrame = frame(50.0), mikrofonDb = 99.0, mikrofonSchwelle = 60f, meterSchwelle = 65f,
        )
        assertFalse(ergebnis.ausgeloest)
    }

    @Test
    fun weightingWirdNurBeiBestaetigterBewertungDurchgereicht() {
        val unbestaetigt = MeterTriggerSource.auswerten(
            letzterMeterFrame = frame(70.0, bestaetigt = false), mikrofonDb = 0.0, mikrofonSchwelle = 60f, meterSchwelle = 65f,
        )
        val bestaetigt = MeterTriggerSource.auswerten(
            letzterMeterFrame = frame(70.0, bestaetigt = true), mikrofonDb = 0.0, mikrofonSchwelle = 60f, meterSchwelle = 65f,
        )
        assertNull("Unbestaetigte Bewertung darf nicht als Tatsache durchgereicht werden", unbestaetigt.meterWeighting)
        assertEquals("A", bestaetigt.meterWeighting)
    }

    @Test
    fun fehlendeWeightingBleibtNullAuchBeiBestaetigterMessung() {
        val ergebnis = MeterTriggerSource.auswerten(
            letzterMeterFrame = frame(70.0, weighting = null, bestaetigt = true),
            mikrofonDb = 0.0, mikrofonSchwelle = 60f, meterSchwelle = 65f,
        )
        assertNull(ergebnis.meterWeighting)
    }

    @Test
    fun genauAufDerSchwelleLoestNichtAus() {
        val ergebnis = MeterTriggerSource.auswerten(
            letzterMeterFrame = frame(65.0), mikrofonDb = 0.0, mikrofonSchwelle = 60f, meterSchwelle = 65f,
        )
        assertFalse("Die Schwelle selbst zaehlt noch nicht als Ueberschreitung", ergebnis.ausgeloest)
    }
}
