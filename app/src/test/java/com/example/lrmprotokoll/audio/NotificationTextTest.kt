package com.example.lrmprotokoll.audio

import com.example.lrmprotokoll.meter.ConnectionState
import com.example.lrmprotokoll.meter.label
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROMPT_M10_FUNKTIONEN.md F4: reine Ableitungslogik fuer den Dauer-Notification-Text und die
 * DEGRADED/FAILED-Erkennung, deshalb als reiner JUnit-Test ohne Robolectric.
 */
class NotificationTextTest {

    @Test
    fun ohneMessgeraetUndOhneMikrofonpegelZeigtDenGenerischenText() {
        val text = leiteNotificationTextAb(
            istMessgeraetGepinnt = false,
            meterState = ConnectionState.IDLE,
            meterPegel = null,
            mikrofonPegel = null,
        )
        assertEquals("Die App überwacht die Umgebungslautstärke im Hintergrund.", text)
    }

    @Test
    fun ohneMessgeraetMitMikrofonpegelZeigtDenPegel() {
        val text = leiteNotificationTextAb(
            istMessgeraetGepinnt = false,
            meterState = ConnectionState.IDLE,
            meterPegel = null,
            mikrofonPegel = 47.3,
        )
        val erwarteterPegel = String.format(Locale.getDefault(), "%.1f dB", 47.3)
        assertTrue(text.contains(erwarteterPegel))
    }

    @Test
    fun mitGepinntemMessgeraetUndStreamingPegelZeigtBeides() {
        val text = leiteNotificationTextAb(
            istMessgeraetGepinnt = true,
            meterState = ConnectionState.STREAMING,
            meterPegel = 61.2,
            mikrofonPegel = 47.3,
        )
        assertTrue(text.contains(ConnectionState.STREAMING.label()))
        val erwarteterPegel = String.format(Locale.getDefault(), "%.1f dB", 61.2)
        assertTrue(text.contains(erwarteterPegel))
        // Der Mikrofonwert ist bei gepinntem Messgeraet nicht relevant fuer den Text.
        assertFalse(text.contains("47,3") || text.contains("47.3"))
    }

    @Test
    fun mitGepinntemMessgeraetOhneStreamingPegelZeigtNurDenZustand() {
        // Gegenprobe: kein Pegel-Zusatz, wenn meterPegel null ist (Verbindung nicht STREAMING).
        val text = leiteNotificationTextAb(
            istMessgeraetGepinnt = true,
            meterState = ConnectionState.RECONNECTING,
            meterPegel = null,
            mikrofonPegel = null,
        )
        assertTrue(text.contains(ConnectionState.RECONNECTING.label()))
        assertFalse(text.contains("dB)"))
    }

    @Test
    fun degradedUndFailedGeltenAlsGestoert() {
        assertTrue(istNotificationZustandGestoert(ConnectionState.DEGRADED))
        assertTrue(istNotificationZustandGestoert(ConnectionState.FAILED))
    }

    @Test
    fun streamingUndIdleGeltenNichtAlsGestoert() {
        // Gegenprobe: schlaegt fehl, wenn istNotificationZustandGestoert immer true liefert.
        assertFalse(istNotificationZustandGestoert(ConnectionState.STREAMING))
        assertFalse(istNotificationZustandGestoert(ConnectionState.IDLE))
        assertFalse(istNotificationZustandGestoert(ConnectionState.CONNECTING))
    }
}
