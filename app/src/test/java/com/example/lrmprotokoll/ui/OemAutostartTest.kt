package com.example.lrmprotokoll.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PROMPT_M8.md Aufgabe 2: [leiteOemAutostartHinweisAb] ist reine Ableitungslogik ohne
 * Android-Abhaengigkeit (Hersteller-String als Parameter statt Build.MANUFACTURER direkt
 * gelesen) - deshalb hier als reiner JUnit-Test ohne Robolectric, analog zu
 * [LiveCockpitFensterTest].
 */
class OemAutostartTest {

    @Test
    fun xiaomiLiefertDenAutostartHinweis() {
        val hinweis = leiteOemAutostartHinweisAb("Xiaomi")
        assertEquals("com.miui.securitycenter", hinweis?.intentPackage)
        assertEquals(
            "com.miui.permcenter.autostart.AutoStartManagementActivity",
            hinweis?.intentActivity,
        )
    }

    @Test
    fun redmiUndPocoZaehlenAlsXiaomiGruppe() {
        assertEquals("com.miui.securitycenter", leiteOemAutostartHinweisAb("Redmi")?.intentPackage)
        assertEquals("com.miui.securitycenter", leiteOemAutostartHinweisAb("POCO")?.intentPackage)
    }

    @Test
    fun huaweiLiefertDenAutostartHinweis() {
        val hinweis = leiteOemAutostartHinweisAb("HUAWEI")
        assertEquals("com.huawei.systemmanager", hinweis?.intentPackage)
    }

    @Test
    fun oppoLiefertDenAutostartHinweis() {
        val hinweis = leiteOemAutostartHinweisAb("OPPO")
        assertEquals("com.coloros.safecenter", hinweis?.intentPackage)
    }

    @Test
    fun vivoLiefertDenAutostartHinweis() {
        val hinweis = leiteOemAutostartHinweisAb("vivo")
        assertEquals("com.vivo.permissionmanager", hinweis?.intentPackage)
    }

    @Test
    fun onePlusLiefertDenAutostartHinweis() {
        val hinweis = leiteOemAutostartHinweisAb("OnePlus")
        assertEquals("com.oneplus.security", hinweis?.intentPackage)
    }

    @Test
    fun samsungLiefertDenAutostartHinweis() {
        val hinweis = leiteOemAutostartHinweisAb("samsung")
        assertEquals("com.samsung.android.lool", hinweis?.intentPackage)
    }

    @Test
    fun grossKleinschreibungSpieltKeineRolle() {
        // Gegenprobe zur ignoreCase-Behandlung: schlaegt fehl, wenn man ignoreCase entfernt.
        assertEquals(leiteOemAutostartHinweisAb("xiaomi"), leiteOemAutostartHinweisAb("XIAOMI"))
    }

    @Test
    fun fuehrendesUndAbschliessendesWhitespaceWirdIgnoriert() {
        val hinweis = leiteOemAutostartHinweisAb("  Xiaomi  ")
        assertEquals("com.miui.securitycenter", hinweis?.intentPackage)
    }

    @Test
    fun unbekannterHerstellerLiefertKeinenHinweis() {
        assertNull(leiteOemAutostartHinweisAb("Google"))
        assertNull(leiteOemAutostartHinweisAb("Fairphone"))
        assertNull(leiteOemAutostartHinweisAb(""))
    }

    @Test
    fun teilstringOhneHerstellerbezugLoestKeinenFalschenTrefferAus() {
        // Gegenprobe gegen zu laxes contains(): "Sony" enthaelt keinen der Hersteller-
        // Substrings, darf also nicht zufaellig auf Oppo/Vivo/etc. matchen.
        assertNull(leiteOemAutostartHinweisAb("Sony"))
    }
}
