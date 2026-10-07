package com.example.lrmprotokoll.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Prueft alle acht Kombinationen von [bewerteAufzeichnungsLage] (Auftrag PROMPT_FIX_AUFZEICHNUNG_FORTSETZEN.md).
 */
class AufzeichnungsSollTest {

    @Test
    fun testAlleAchtKombinationen() {
        data class Fall(
            val monitoringWasActive: Boolean,
            val dienstLaeuft: Boolean,
            val kannInDenVordergrund: Boolean,
            val erwartet: AufzeichnungsLage,
        )

        val faelle = listOf(
            // Dienst laeuft bereits -> immer LAEUFT, unabhaengig von den anderen Parametern
            Fall(monitoringWasActive = true, dienstLaeuft = true, kannInDenVordergrund = true, erwartet = AufzeichnungsLage.LAEUFT),
            Fall(monitoringWasActive = true, dienstLaeuft = true, kannInDenVordergrund = false, erwartet = AufzeichnungsLage.LAEUFT),
            Fall(monitoringWasActive = false, dienstLaeuft = true, kannInDenVordergrund = true, erwartet = AufzeichnungsLage.LAEUFT),
            Fall(monitoringWasActive = false, dienstLaeuft = true, kannInDenVordergrund = false, erwartet = AufzeichnungsLage.LAEUFT),

            // Dienst laeuft nicht, war nicht aktiv -> immer AUS
            Fall(monitoringWasActive = false, dienstLaeuft = false, kannInDenVordergrund = true, erwartet = AufzeichnungsLage.AUS),
            Fall(monitoringWasActive = false, dienstLaeuft = false, kannInDenVordergrund = false, erwartet = AufzeichnungsLage.AUS),

            // Dienst laeuft nicht, war aktiv, kann in den Vordergrund -> SOLL_LAEUFT_NICHT
            Fall(monitoringWasActive = true, dienstLaeuft = false, kannInDenVordergrund = true, erwartet = AufzeichnungsLage.SOLL_LAEUFT_NICHT),

            // Dienst laeuft nicht, war aktiv, kann aber NICHT in den Vordergrund -> SOLL_ABER_NICHT_STARTBAR
            Fall(monitoringWasActive = true, dienstLaeuft = false, kannInDenVordergrund = false, erwartet = AufzeichnungsLage.SOLL_ABER_NICHT_STARTBAR),
        )

        assertEquals("Muss genau 8 Faelle abdecken", 8, faelle.size)

        for ((index, fall) in faelle.withIndex()) {
            val ergebnis = bewerteAufzeichnungsLage(
                monitoringWasActive = fall.monitoringWasActive,
                dienstLaeuft = fall.dienstLaeuft,
                kannInDenVordergrund = fall.kannInDenVordergrund,
            )
            assertEquals("Fehler in Fall $index: $fall", fall.erwartet, ergebnis)
        }
    }
}
