package com.example.lrmprotokoll.alert.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNotificationAlertChannelTest {

    @Test
    fun mustertDauertMindestensDieAngeforderteZeit() {
        val muster = alarmVibrationsmuster(mindestdauerMillis = 60_000L)
        // muster[0] ist die Anfangsverzoegerung (0), der Rest wechselt vibrieren/pause -
        // die Summe AB Index 1 ist die tatsaechliche Gesamtdauer.
        val gesamtdauer = muster.drop(1).sum()
        assertTrue("Gesamtdauer $gesamtdauer sollte mindestens 60000ms betragen", gesamtdauer >= 60_000L)
    }

    @Test
    fun beginntOhneVerzoegerung() {
        val muster = alarmVibrationsmuster()
        assertEquals(0L, muster[0])
    }

    @Test
    fun wechseltStrengZwischenVibrierenUndPause() {
        val muster = alarmVibrationsmuster(mindestdauerMillis = 5_000L)
        // Ab Index 1: ungerade Positionen (1,3,5,...) sind Vibrieren, gerade (2,4,6,...) Pause -
        // beide muessen positiv sein, sonst gaebe es Luecken ohne Wirkung oder Dauervibration.
        for (i in 1 until muster.size step 2) {
            assertTrue("Vibrations-Segment bei Index $i muss positiv sein", muster[i] > 0)
        }
        for (i in 2 until muster.size step 2) {
            assertTrue("Pause-Segment bei Index $i muss positiv sein", muster[i] > 0)
        }
    }

    @Test
    fun kuerzereMindestdauerErgibtKuerzeresMuster() {
        val kurz = alarmVibrationsmuster(mindestdauerMillis = 1_200L)
        val lang = alarmVibrationsmuster(mindestdauerMillis = 60_000L)
        assertTrue(kurz.size < lang.size)
    }
}
