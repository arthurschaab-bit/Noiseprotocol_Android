package com.example.lrmprotokoll.meter.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Ein waehrend des Scans gefundenes Geraet. */
data class BleDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
)

/**
 * Instrumentierungs-Hook fuer den BLE-Scan.
 *
 * Der Produktionspfad bleibt unveraendert, solange [scanProvider] null ist. Instrumentierte Tests
 * koennen damit Scan-Ergebnisse und `onScanFailed`-Fehler deterministisch einspeisen, ohne den
 * Bluetooth-Adapter des ATD-Emulators oder reale Hardware anzusprechen.
 */
internal object BleScannerTestOverrides {
    @Volatile
    var scanProvider: (() -> Flow<BleDevice>)? = null

    fun reset() {
        scanProvider = null
    }
}

/**
 * BLE-Scan fuer die Kopplung (Plan Abschnitt 6, Geraete-Pinning). Filtert bewusst NICHT nach
 * dem Custom-Service 0000fff0: Ob der Service im Advertisement steht, ist unbekannt
 * (docs/PROTOKOLL_PCE-323.md) - ein Service-Filter, der deshalb nichts findet, ist schlimmer
 * als eine ungefilterte Liste, aus der der Nutzer per Name/Adresse waehlt. Die Auswahl
 * persistiert die MAC-Adresse; danach wird ausschliesslich noch zu dieser Adresse verbunden.
 */
class BleScanner(private val context: Context) {

    fun scan(): Flow<BleDevice> = BleScannerTestOverrides.scanProvider?.invoke() ?: realScan()

    @SuppressLint("MissingPermission") // Aufrufer (UI) prueft BLUETOOTH_SCAN vor jedem Zugriff
    private fun realScan(): Flow<BleDevice> = callbackFlow {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            close(IllegalStateException("Kein Bluetooth-Adapter verfügbar."))
            return@callbackFlow
        }
        if (!adapter.isEnabled) {
            close(IllegalStateException("Bluetooth ist deaktiviert. Bitte in den Systemeinstellungen aktivieren."))
            return@callbackFlow
        }

        if (BluetoothPermissions.isLocationRequiredForScan() && !BluetoothPermissions.isLocationEnabled(context)) {
            close(IllegalStateException("Standortdienste (GPS) müssen auf diesem Gerät für die Bluetooth-Suche aktiviert sein."))
            return@callbackFlow
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("Bluetooth-LE-Scanner ist nicht verfügbar."))
            return@callbackFlow
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val deviceName = try {
                    result.scanRecord?.deviceName ?: result.device.name
                } catch (e: SecurityException) {
                    null
                }
                trySend(
                    BleDevice(
                        address = result.device.address,
                        name = deviceName,
                        rssi = result.rssi,
                    )
                )
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("BLE-Scan fehlgeschlagen, errorCode=$errorCode"))
            }
        }

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(null, scanSettings, callback)
        } catch (e: SecurityException) {
            close(IllegalStateException("Bluetooth-Berechtigung fehlt oder wurde verweigert.", e))
            return@callbackFlow
        }

        awaitClose {
            try {
                scanner.stopScan(callback)
            } catch (e: Throwable) {
                // Ignore errors during flow cancellation / shutdown
            }
        }
    }
}
