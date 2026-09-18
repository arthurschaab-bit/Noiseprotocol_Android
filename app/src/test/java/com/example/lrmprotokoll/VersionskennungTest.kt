package com.example.lrmprotokoll

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionskennungTest {

    @Test
    fun formatiertCiPrLaufMitLaufnummerInKlammern() {
        assertEquals(
            "1.0.0-pr181.ci342+a1b2c3d (342)",
            Versionskennung.formatiere("1.0.0-pr181.ci342+a1b2c3d", 342),
        )
    }

    @Test
    fun ciPrLaufIstKeinReleaseBuild() {
        assertFalse(Versionskennung.istReleaseBuild("1.0.0-pr181.ci342+a1b2c3d"))
    }

    @Test
    fun formatiertCiPushAufMain() {
        assertEquals(
            "1.0.0-main.ci343+e4f5a6b (343)",
            Versionskennung.formatiere("1.0.0-main.ci343+e4f5a6b", 343),
        )
    }

    @Test
    fun ciPushAufMainIstKeinReleaseBuild() {
        assertFalse(Versionskennung.istReleaseBuild("1.0.0-main.ci343+e4f5a6b"))
    }

    @Test
    fun formatiertLokalSauberenBaum() {
        assertEquals(
            "1.0.0-lokal+a1b2c3d (1)",
            Versionskennung.formatiere("1.0.0-lokal+a1b2c3d", 1),
        )
    }

    @Test
    fun lokalSauberIstKeinReleaseBuild() {
        assertFalse(Versionskennung.istReleaseBuild("1.0.0-lokal+a1b2c3d"))
    }

    @Test
    fun formatiertLokalMitUncommittetenAenderungen() {
        assertEquals(
            "1.0.0-lokal+a1b2c3d.dirty (1)",
            Versionskennung.formatiere("1.0.0-lokal+a1b2c3d.dirty", 1),
        )
    }

    @Test
    fun lokalDirtyIstKeinReleaseBuild() {
        assertFalse(Versionskennung.istReleaseBuild("1.0.0-lokal+a1b2c3d.dirty"))
    }

    @Test
    fun formatiertLokalOhneGit() {
        assertEquals("1.0.0-lokal (1)", Versionskennung.formatiere("1.0.0-lokal", 1))
    }

    @Test
    fun lokalOhneGitIstKeinReleaseBuild() {
        assertFalse(Versionskennung.istReleaseBuild("1.0.0-lokal"))
    }

    @Test
    fun formatiertReleaseMitVollemVersionCode() {
        assertEquals("1.0.0 (10000)", Versionskennung.formatiere("1.0.0", 10000))
    }

    @Test
    fun reinesXPunktYPunktZIstReleaseBuild() {
        assertTrue(Versionskennung.istReleaseBuild("1.0.0"))
    }

    @Test
    fun releaseErkennungIstNichtNurEinPraefixVergleich() {
        // Ein Suffix nach der reinen X.Y.Z-Form darf NICHT als Release durchgehen - sonst wuerde
        // z.B. ein manipulierter/unvollstaendiger Build faelschlich als offizieller Stand gelten.
        assertFalse(Versionskennung.istReleaseBuild("1.0.0-lokal"))
        assertFalse(Versionskennung.istReleaseBuild("1.0.0."))
        assertFalse(Versionskennung.istReleaseBuild("1.0"))
    }
}
