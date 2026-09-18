package com.example.lrmprotokoll.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReportAreaTest {
    @Test fun bestaetigteKuerzelWerdenNormalisiert() {
        assertEquals(ReportArea.MI, ReportArea.fromCode(" mi "))
        listOf("WA", "WR", "MI", "GE", "GI").forEach { assertNull(areaSelectionError(it)) }
    }

    @Test fun unbekannteUndUnbelegteGebieteWerdenNichtUmgedeutet() {
        listOf("", "WAA", "WA/MI", "Allgemeines Wohngebiet (WA)", "WS", "WB", "MD", "MDW", "MU", "MK")
            .forEach { assertNotNull("Kein Fallback für $it", areaSelectionError(it)) }
        assertNull(ReportArea.fromCode("Allgemeines Wohngebiet (WA)"))
    }
}
