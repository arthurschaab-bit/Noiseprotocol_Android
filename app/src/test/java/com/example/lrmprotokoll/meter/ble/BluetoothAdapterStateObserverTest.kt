package com.example.lrmprotokoll.meter.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Testluecken-Auftrag Stufe 4: [BluetoothAdapterStateObserver] war komplett ungetestet - dabei
 * haengt [com.example.lrmprotokoll.meter.ConnectionSupervisor]s "Reconnect-Schleife bei
 * Bluetooth-aus pausieren" (Plan 5.3) direkt an dessen [BluetoothAdapterStateObserver.enabled].
 * Ein falscher Anfangswert oder ein verpasster Broadcast wuerde entweder Reconnect-Versuche
 * sinnlos gegen ein abgeschaltetes Bluetooth verbrennen oder die Ueberwachung nach dem
 * Wiedereinschalten nicht fortsetzen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BluetoothAdapterStateObserverTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun setzeAdapterEnabled(wert: Boolean) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        shadowOf(adapter).setEnabled(wert)
    }

    /** sendBroadcast() liefert unter Robolectrics Standard-LooperMode (PAUSED) nicht synchron
     * aus - erst idle() auf dem Main-Looper stoesst die Zustellung an den dynamisch registrierten
     * BroadcastReceiver tatsaechlich an. */
    private fun sendeUndVerarbeite(state: Int) {
        context.sendBroadcast(Intent(BluetoothAdapter.ACTION_STATE_CHANGED).putExtra(BluetoothAdapter.EXTRA_STATE, state))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun spiegeltDenAnfangszustandDesAdaptersBeimErstellen() {
        setzeAdapterEnabled(true)

        val observer = BluetoothAdapterStateObserver(context)

        assertTrue(observer.enabled.value)
    }

    @Test
    fun spiegeltAusgeschaltetenAdapterBeimErstellen() {
        setzeAdapterEnabled(false)

        val observer = BluetoothAdapterStateObserver(context)

        assertFalse(observer.enabled.value)
    }

    @Test
    fun stateOffBroadcastSchaltetEnabledAufFalse() {
        setzeAdapterEnabled(true)
        val observer = BluetoothAdapterStateObserver(context)

        sendeUndVerarbeite(BluetoothAdapter.STATE_OFF)

        assertFalse(observer.enabled.value)
    }

    @Test
    fun stateOnBroadcastSchaltetEnabledAufTrueZurueck() {
        setzeAdapterEnabled(false)
        val observer = BluetoothAdapterStateObserver(context)
        sendeUndVerarbeite(BluetoothAdapter.STATE_OFF)

        sendeUndVerarbeite(BluetoothAdapter.STATE_ON)

        assertTrue(observer.enabled.value)
    }

    @Test
    fun zwischenzustaendeTurningOnOderOffWerdenIgnoriert() {
        setzeAdapterEnabled(true)
        val observer = BluetoothAdapterStateObserver(context)

        sendeUndVerarbeite(BluetoothAdapter.STATE_TURNING_OFF)

        assertEquals(
            "STATE_TURNING_OFF ist noch kein endgueltiger Zustand - nur STATE_ON/STATE_OFF " +
                "duerfen enabled aendern",
            true, observer.enabled.value,
        )
    }
}
