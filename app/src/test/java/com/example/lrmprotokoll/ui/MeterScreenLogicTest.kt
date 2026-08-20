package com.example.lrmprotokoll.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reine JVM-Tests fuer [scanFehlermeldung] - kein Robolectric noetig, keine Android-/BLE-
 * Abhaengigkeit. Regressionstest fuer die Gerätetest-Rückmeldung "Wenn ich Scannen crashed die
 * App": der Scan-Button in [MeterScreen] fing frueher keine Exception aus dem Scan-Flow ab
 * (bloßes try{}finally{} ohne catch) - u.a. BleScanner.onScanFailed() (z.B.
 * SCAN_FAILED_SCANNING_TOO_FREQUENTLY bei manchen Herstellern) propagierte dadurch unbehandelt
 * und beendete den Prozess. Dieser Test deckt nur die Fehlermeldungs-Ableitung ab - dass die
 * Exception im Compose-Code tatsaechlich abgefangen wird, ist nur per Code-Review belegt (siehe
 * PR-Beschreibung), nicht per Test: ein echter BLE-Scan-Fehlschlag laesst sich unter Robolectric
 * nicht auslösen.
 */
class MeterScreenLogicTest {

    @Test
    fun securityExceptionErgibtBerechtigungsHinweis() {
        val meldung = scanFehlermeldung(SecurityException("permission denied"))
        assertTrue(meldung.contains("Berechtigung"))
    }

    @Test
    fun sonstigeExceptionZeigtDieUrspruenglicheMeldung() {
        val meldung = scanFehlermeldung(IllegalStateException("BLE-Scan fehlgeschlagen, errorCode=6"))
        assertEquals("Scan fehlgeschlagen: BLE-Scan fehlgeschlagen, errorCode=6", meldung)
    }

    @Test
    fun exceptionOhneMessageFaelltAufDenKlassennamenZurueck() {
        val meldung = scanFehlermeldung(object : RuntimeException() {
            override val message: String? = null
        })
        assertTrue(meldung.startsWith("Scan fehlgeschlagen: "))
        assertTrue(meldung != "Scan fehlgeschlagen: ")
    }
}
