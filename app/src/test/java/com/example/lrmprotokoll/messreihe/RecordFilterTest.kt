package com.example.lrmprotokoll.messreihe

import com.example.lrmprotokoll.data.NoiseRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordFilterTest {

    private fun sampleRecord(
        id: Long,
        dbValue: Double = 50.0,
        calibratedDbA: Double? = null,
        label: String? = null,
        detectedLabel: String? = null,
        meterConnected: Boolean = false,
        isQuietHour: Boolean = false,
        favorite: Boolean = false,
        notes: String? = null,
    ) = NoiseRecord(
        id = id,
        timestamp = 1700000000000L + id * 1000,
        amplitude = 1000.0,
        dbValue = dbValue,
        filePath = "/path/$id.wav",
        label = label,
        detectedLabel = detectedLabel,
        calibratedDbA = calibratedDbA,
        meterConnected = meterConnected,
        isQuietHour = isQuietHour,
        favorite = favorite,
        notes = notes,
    )

    @Test
    fun inaktiverFilterGibtUnveraenderteListeZurueck() {
        val records = listOf(sampleRecord(1), sampleRecord(2))
        val filter = RecordFilterState()
        assertFalse(filter.istAktiv)
        val gefiltert = filtereNoiseRecords(records, filter)
        assertEquals(2, gefiltert.size)
    }

    @Test
    fun textsucheFiltertUeberLabelUndNotizen() {
        val r1 = sampleRecord(1, label = "Bohrmaschine")
        val r2 = sampleRecord(2, detectedLabel = "Hämmern")
        val r3 = sampleRecord(3, notes = "Sehr lautes Rumpeln")
        val r4 = sampleRecord(4, label = "Verkehr")

        val records = listOf(r1, r2, r3, r4)

        val ergebnisBohr = filtereNoiseRecords(records, RecordFilterState(query = "bohr"))
        assertEquals(listOf(r1), ergebnisBohr)

        val ergebnisRumpel = filtereNoiseRecords(records, RecordFilterState(query = "Rumpeln"))
        assertEquals(listOf(r3), ergebnisRumpel)
    }

    @Test
    fun pegelFilterGreiftAufKalibriertenOderUnkalibriertenWertZu() {
        val r1 = sampleRecord(1, dbValue = 40.0)
        val r2 = sampleRecord(2, dbValue = 50.0, calibratedDbA = 65.0)
        val r3 = sampleRecord(3, dbValue = 70.0)

        val records = listOf(r1, r2, r3)

        // Filter 60..80 dB -> r2 (65 dBA) und r3 (70 dB)
        val gefiltert = filtereNoiseRecords(records, RecordFilterState(minDb = 60.0f, maxDb = 80.0f))
        assertEquals(listOf(r2, r3), gefiltert)
    }

    @Test
    fun schalterFilterFavoritenUndRuhezeiten() {
        val r1 = sampleRecord(1, favorite = true, isQuietHour = false)
        val r2 = sampleRecord(2, favorite = false, isQuietHour = true)
        val r3 = sampleRecord(3, favorite = true, isQuietHour = true)

        val records = listOf(r1, r2, r3)

        val nurFav = filtereNoiseRecords(records, RecordFilterState(onlyFavorites = true))
        assertEquals(listOf(r1, r3), nurFav)

        val nurRuhe = filtereNoiseRecords(records, RecordFilterState(onlyQuietHours = true))
        assertEquals(listOf(r2, r3), nurRuhe)

        val favUndRuhe = filtereNoiseRecords(records, RecordFilterState(onlyFavorites = true, onlyQuietHours = true))
        assertEquals(listOf(r3), favUndRuhe)
    }
}
