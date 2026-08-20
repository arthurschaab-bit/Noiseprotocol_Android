package com.example.lrmprotokoll.meter.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Beobachtet [BluetoothAdapter.ACTION_STATE_CHANGED] und stellt den Ein/Aus-Zustand als
 * [StateFlow] bereit (Plan Abschnitt 5.3): [com.example.lrmprotokoll.meter.ConnectionSupervisor]
 * pausiert seine Reconnect-Schleife bei STATE_OFF, statt Versuche zu verbrennen, und nimmt bei
 * STATE_ON sofort wieder auf.
 *
 * Bewusst als eigene, Android-spezifische Klasse getrennt vom Supervisor (der nur die
 * plattformunabhaengige `MeterTransport`-Schnittstelle kennt, damit er gegen den Fake testbar
 * bleibt). Der BroadcastReceiver wird fuer die Lebensdauer der App registriert und nie
 * explizit abgemeldet - [AppContainer] haelt genau eine Instanz fuer den ganzen Prozess.
 */
class BluetoothAdapterStateObserver(context: Context) {
    private val appContext = context.applicationContext

    private val _enabled = MutableStateFlow(currentlyEnabled())
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> _enabled.value = true
                BluetoothAdapter.STATE_OFF -> _enabled.value = false
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun currentlyEnabled(): Boolean {
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
        // isEnabled() braucht laut Android-Dokumentation keine Laufzeitberechtigung, anders als
        // die meisten anderen Adapter-Methoden - defensiv trotzdem abgesichert, falls ein
        // Hersteller-ROM davon abweicht.
        return try {
            adapter?.isEnabled ?: false
        } catch (e: SecurityException) {
            false
        }
    }
}
