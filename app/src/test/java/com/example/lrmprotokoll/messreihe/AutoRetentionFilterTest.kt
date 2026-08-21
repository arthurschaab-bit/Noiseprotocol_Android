package com.example.lrmprotokoll.messreihe

import com.example.lrmprotokoll.data.NoiseRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoRetentionFilterTest {

    private fun sampleRecord(
        id: Long,
        timestamp: Long,
        label: String? = null,
        detectedLabel: String? = null,
        favorite: Boolean = false,
        deletedAt: Long? = null,
        filePath: String = "/path/$id.wav"
    ) = NoiseRecord(
        id = id,
        timestamp = timestamp,
        amplitude = 1000.0,
        dbValue = 50.0,
        filePath = filePath,
        label = label,
        detectedLabel = detectedLabel,
        favorite = favorite,
        deletedAt = deletedAt
    )

    @Test
    fun geschuetzteAufnahmenBleibenUnberuehrt() {
        val now = 1_700_000_000_000L
        val cutoff = now - (30L * 24 * 3600 * 1000)

        val altUnwichtig = sampleRecord(1, timestamp = cutoff - 1000)
        val neuUnwichtig = sampleRecord(2, timestamp = cutoff + 1000)
        val altMitLabel = sampleRecord(3, timestamp = cutoff - 1000, label = "Bagger")
        val altMitKi = sampleRecord(4, timestamp = cutoff - 1000, detectedLabel = "Hämmern")
        val altFavorit = sampleRecord(5, timestamp = cutoff - 1000, favorite = true)
        val altGeloescht = sampleRecord(6, timestamp = cutoff - 1000, deletedAt = now - 500)
        val altMuster = sampleRecord(7, timestamp = cutoff - 1000, filePath = "/path/referenz.wav")

        val alle = listOf(altUnwichtig, neuUnwichtig, altMitLabel, altMitKi, altFavorit, altGeloescht, altMuster)

        val kandidaten = ermittleAutoRetentionKandidaten(
            records = alle,
            cutoffTimestamp = cutoff,
            gelernteMuster = setOf("/path/referenz.wav")
        )

        assertEquals(listOf(altUnwichtig), kandidaten)
    }
}
