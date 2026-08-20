package com.example.lrmprotokoll.meter.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
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
 * BLE-Scan fuer die Kopplung (Plan Abschnitt 6, Geraete-Pinning). Filtert bewusst NICHT nach
 * dem Custom-Service 0000fff0: Ob der Service im Advertisement steht, ist unbekannt
 * (docs/PROTOKOLL_PCE-323.md) - ein Service-Filter, der deshalb nichts findet, ist schlimmer
 * als eine ungefilterte Liste, aus der der Nutzer per Name/Adresse waehlt. Die Auswahl
 * persistiert die MAC-Adresse; danach wird ausschliesslich noch zu dieser Adresse verbunden.
 */
class BleScanner(private val context: Context) {

    @SuppressLint("MissingPermission") // Aufrufer (UI) prueft BLUETOOTH_SCAN vor jedem Zugriff
    fun scan(): Flow<BleDevice> = callbackFlow {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            close()
            return@callbackFlow
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(
                    BleDevice(
                        address = result.device.address,
                        name = result.device.name,
                        rssi = result.rssi,
                    )
                )
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("BLE-Scan fehlgeschlagen, errorCode=$errorCode"))
            }
        }

        scanner.startScan(callback)
        awaitClose { scanner.stopScan(callback) }
    }
}
