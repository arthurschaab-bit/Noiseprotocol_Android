package com.example.lrmprotokoll

import android.content.Context
import android.util.Log
import com.example.lrmprotokoll.data.AppDatabase
import com.example.lrmprotokoll.data.SettingsManager
import com.example.lrmprotokoll.meter.ConnectionSupervisor
import com.example.lrmprotokoll.meter.MeterTransport
import com.example.lrmprotokoll.meter.ble.BleMeterTransport
import com.example.lrmprotokoll.meter.ble.BluetoothAdapterStateObserver
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Schlanker manueller Ersatz fuer ein DI-Framework (Plan Abschnitt 4.2): haelt die
 * Anwendung geteilten Abhaengigkeiten an einer Stelle, damit sie in Tests austauschbar sind,
 * ohne alle bestehenden Klassen mit Annotationen zu versehen.
 */
class AppContainer(context: Context) {
    val database: AppDatabase by lazy { AppDatabase.getDatabase(context) }
    val settingsManager: SettingsManager by lazy { SettingsManager(context) }
    val meterTransport: MeterTransport by lazy { BleMeterTransport(context.applicationContext) }

    private val bluetoothAdapterStateObserver by lazy {
        BluetoothAdapterStateObserver(context.applicationContext)
    }

    // App-weiter Scope statt Activity-/Service-gebunden: der Verbindungsaufbau muss eine
    // geschlossene UI und Konfigurationswechsel ueberleben (PROMPT_M3 Aufgabe 3). Gestartet/
    // gestoppt wird er trotzdem vom AudioRecordingService, damit er die Absicherung eines
    // Foreground Service bekommt statt unprotokolliert im Hintergrund gedrosselt zu werden.
    //
    // CoroutineExceptionHandler als zweites Netz (Review-Befund 2, PR #16): ConnectionSupervisor
    // faengt Exceptions aus einzelnen Verbindungsversuchen bereits selbst ab, aber ohne Handler
    // hier wuerde eine Ausnahme aus einem anderen Pfad (z.B. dem forwarder-launch) den Scope
    // unbemerkt verlassen, statt wenigstens geloggt zu werden - der SupervisorJob haelt den
    // Scope zwar am Leben, aber nur der Handler verhindert, dass der Fehler spurlos verschwindet.
    private val connectionSupervisorScope by lazy {
        val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            Log.e("AppContainer", "Unerwarteter Fehler im ConnectionSupervisor-Scope", throwable)
        }
        CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)
    }

    val connectionSupervisor: ConnectionSupervisor by lazy {
        ConnectionSupervisor(
            transport = meterTransport,
            scope = connectionSupervisorScope,
            adapterEnabled = bluetoothAdapterStateObserver.enabled,
        )
    }
}
