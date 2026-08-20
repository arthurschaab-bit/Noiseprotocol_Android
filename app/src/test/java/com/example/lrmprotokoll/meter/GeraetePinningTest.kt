package com.example.lrmprotokoll.meter

import org.junit.Assert.assertEquals
import org.junit.Test

class GeraetePinningTest {

    @Test
    fun keinGepinntesGeraetIstImmerNeu() {
        val befund = GeraetePinning.beurteile(
            gefundeneAdresse = "AA:BB:CC:DD:EE:FF",
            gefundenerName = "PCE-323",
            gepinnteAdresse = null,
            gepinnterName = null,
        )
        assertEquals(PinningBefund.NEUES_GERAET, befund)
    }

    @Test
    fun gleicheAdresseIstDasGepinnteGeraet() {
        val befund = GeraetePinning.beurteile(
            gefundeneAdresse = "AA:BB:CC:DD:EE:FF",
            gefundenerName = "PCE-323",
            gepinnteAdresse = "AA:BB:CC:DD:EE:FF",
            gepinnterName = "PCE-323",
        )
        assertEquals(PinningBefund.GEPINNTES_GERAET, befund)
    }

    @Test
    fun gleicheAdresseTrotzAbweichendGemeldetemNamenBleibtDasGepinnteGeraet() {
        // Der Name kann sich am Geraet aendern (z.B. Werksreset) - massgeblich fuer "ist es das
        // gepinnte Geraet" ist ausschliesslich die Adresse, siehe BleMeterTransport.connect().
        val befund = GeraetePinning.beurteile(
            gefundeneAdresse = "AA:BB:CC:DD:EE:FF",
            gefundenerName = "Irgendwas",
            gepinnteAdresse = "AA:BB:CC:DD:EE:FF",
            gepinnterName = "PCE-323",
        )
        assertEquals(PinningBefund.GEPINNTES_GERAET, befund)
    }

    @Test
    fun andereAdresseGleicherNameIstVerdaechtig() {
        val befund = GeraetePinning.beurteile(
            gefundeneAdresse = "11:22:33:44:55:66",
            gefundenerName = "PCE-323",
            gepinnteAdresse = "AA:BB:CC:DD:EE:FF",
            gepinnterName = "PCE-323",
        )
        assertEquals(PinningBefund.VERDAECHTIG_GLEICHER_NAME, befund)
    }

    @Test
    fun andereAdresseAnderarNameIstEinfachEinNeuesGeraet() {
        val befund = GeraetePinning.beurteile(
            gefundeneAdresse = "11:22:33:44:55:66",
            gefundenerName = "Anderes Geraet",
            gepinnteAdresse = "AA:BB:CC:DD:EE:FF",
            gepinnterName = "PCE-323",
        )
        assertEquals(PinningBefund.NEUES_GERAET, befund)
    }

    @Test
    fun andereAdresseOhneNamenIstEinfachEinNeuesGeraet() {
        // null == null waere in Kotlin "gleich" - ohne den Null-Check in GeraetePinning wuerden
        // zwei namenlose Geraete faelschlich als VERDAECHTIG_GLEICHER_NAME markiert.
        val befund = GeraetePinning.beurteile(
            gefundeneAdresse = "11:22:33:44:55:66",
            gefundenerName = null,
            gepinnteAdresse = "AA:BB:CC:DD:EE:FF",
            gepinnterName = null,
        )
        assertEquals(PinningBefund.NEUES_GERAET, befund)
    }
}
