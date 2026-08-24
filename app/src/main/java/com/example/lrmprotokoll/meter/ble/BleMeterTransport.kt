package com.example.lrmprotokoll.meter.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import com.example.lrmprotokoll.meter.BoundDevice
import com.example.lrmprotokoll.meter.ConnectionState
import com.example.lrmprotokoll.meter.FrameQuality
import com.example.lrmprotokoll.meter.MeterCommand
import com.example.lrmprotokoll.meter.MeterFrame
import com.example.lrmprotokoll.meter.MeterTransport
import com.example.lrmprotokoll.meter.Pce323FrameDecoder
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "BleMeterTransport"
private const val CONNECT_TIMEOUT_MS = 10_000L
private const val PREFERRED_MTU = 64
private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/**
 * Reale BLE-Anbindung an das PCE-323 (Plan Abschnitt 4.3, Geraeteprofil [Pce323Profile]).
 *
 * Ablauf: connectGatt -> discoverServices -> (optional) requestMtu -> CCCD-Write auf
 * [Pce323Profile.NOTIFY_CHARACTERISTIC_UUID] -> Frames fliessen. Kein Kommando noetig, um den
 * Strom zu starten (M0). [gatt] wird vor jedem neuen Verbindungsversuch geschlossen, nicht nur
 * getrennt - sonst leckt der Client-Interface-Slot und der Stack liefert nach einigen Zyklen
 * nur noch status 133 (Plan Abschnitt 5.3).
 *
 * createBond() wird bewusst NICHT aufgerufen: In M0 fuehrte der Versuch zu sofortigem
 * Disconnect (status 19) und AUTH_FAILED. Das Modul kann kein Pairing.
 */
@SuppressLint("MissingPermission") // Aufrufer (UI) prueft BLUETOOTH_CONNECT vor jedem Zugriff
class BleMeterTransport(
    private val context: Context,
) : MeterTransport {

    private val _state = MutableStateFlow(ConnectionState.IDLE)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _frames = MutableSharedFlow<MeterFrame>(extraBufferCapacity = 64)
    override val frames: SharedFlow<MeterFrame> = _frames.asSharedFlow()

    private val _lastFrameAt = MutableStateFlow<Instant?>(null)
    override val lastFrameAt: StateFlow<Instant?> = _lastFrameAt.asStateFlow()

    private val _frameQuality = MutableStateFlow(FrameQuality())
    override val frameQuality: StateFlow<FrameQuality> = _frameQuality.asStateFlow()

    private val decoder = Pce323FrameDecoder()
    private val gattQueue = GattQueue()
    private var gatt: BluetoothGatt? = null
    private var connecting: CompletableDeferred<Boolean>? = null
    private var validFrameCount = 0L

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connecting?.complete(status == BluetoothGatt.GATT_SUCCESS)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "Verbindung getrennt, status=$status")
                    connecting?.complete(false)
                    _state.value = ConnectionState.DISCONNECTED
                    // Spontaner Abbruch (Geraet aus, Funkloch) - nicht ueber disconnect()
                    // ausgeloest, das g bereits schliesst. Ohne close() hier leckt der
                    // Client-Interface-Slot bei jedem spontanen Abbruch, und der Stack liefert
                    // nach ~30 Zyklen nur noch status 133 (Plan Abschnitt 5.3, M2-Review Befund 5) -
                    // mit dem automatischen Reconnect aus M3 durchlaeuft die App diese Zyklen.
                    try {
                        g.close()
                    } catch (e: SecurityException) {
                        Log.w(TAG, "Bluetooth-Berechtigung beim spontanen Trennen fehlt", e)
                    }
                    if (gatt === g) gatt = null
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            gattQueue.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            gattQueue.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            gattQueue.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        @Suppress("DEPRECATION") // Zwei-Parameter-Variante noetig, da minSdk 31 < 33
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (characteristic.uuid != Pce323Profile.NOTIFY_CHARACTERISTIC_UUID) return
            val value = characteristic.value ?: return
            onNotify(value)
        }
    }

    override suspend fun connect(device: BoundDevice) {
        disconnect()

        // Zustand aus einer vorherigen Verbindung verwerfen: angefangenes Frame im Puffer,
        // letzter Pegel, Fehlerzaehler - und die nach einem Timeout gesperrte GATT-Queue.
        decoder.reset()
        gattQueue.reset()
        validFrameCount = 0
        _frameQuality.value = FrameQuality()

        _state.value = ConnectionState.CONNECTING

        try {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            val remote = adapter?.getRemoteDevice(device.address)
            if (adapter == null || remote == null) {
                _state.value = ConnectionState.FAILED
                return
            }

            val connectedDeferred = CompletableDeferred<Boolean>()
            connecting = connectedDeferred
            gatt = remote.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)

            val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connectedDeferred.await() } ?: false
            connecting = null
            if (!connected) {
                _state.value = ConnectionState.FAILED
                return
            }

            _state.value = ConnectionState.DISCOVERING
            val discovered = gattQueue.execute { gatt?.discoverServices() ?: false }
            if (!discovered) {
                _state.value = ConnectionState.FAILED
                return
            }

            // Best effort: gelingt requestMtu, kommt das Frame womoeglich in einer einzigen
            // Notification statt zwei. Der Decoder deckt beide Faelle ab (M0), das Ergebnis
            // wird deshalb nicht ausgewertet und blockiert bei Timeout nicht die Subskription.
            gattQueue.executeOptional { gatt?.requestMtu(PREFERRED_MTU) ?: false }

            val notifyCharacteristic = gatt
                ?.getService(Pce323Profile.SERVICE_UUID)
                ?.getCharacteristic(Pce323Profile.NOTIFY_CHARACTERISTIC_UUID)
            if (notifyCharacteristic == null) {
                Log.w(TAG, "Notify-Characteristic ${Pce323Profile.NOTIFY_CHARACTERISTIC_UUID} nicht gefunden")
                _state.value = ConnectionState.FAILED
                return
            }

            val cccd = notifyCharacteristic.getDescriptor(CCCD_UUID)
            if (cccd == null) {
                _state.value = ConnectionState.FAILED
                return
            }

            _state.value = ConnectionState.SUBSCRIBING
            gatt?.setCharacteristicNotification(notifyCharacteristic, true)
            @Suppress("DEPRECATION") // Zwei-Parameter-writeDescriptor noetig, da minSdk 31 < 33
            val subscribed = gattQueue.execute {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt?.writeDescriptor(cccd) ?: false
            }
            if (!subscribed) {
                _state.value = ConnectionState.FAILED
                return
            }

            // Zustand bleibt SUBSCRIBING, bis der erste valide Frame eintrifft (onNotify) - eine
            // stehende Verbindung ohne Datenfluss ist ein Ausfall, kein Erfolg (Plan 5.1).
        } catch (e: SecurityException) {
            Log.e(TAG, "Bluetooth-Berechtigung fehlt oder wurde entzogen", e)
            _state.value = ConnectionState.FAILED
        }
    }

    private fun onNotify(raw: ByteArray) {
        val decoded = decoder.feed(raw)
        for (frame in decoded) {
            validFrameCount++
            _state.value = ConnectionState.STREAMING
            _lastFrameAt.value = frame.receivedAt
            // tryEmit statt launch+emit: nicht-suspendierend, behaelt damit die Reihenfolge des
            // Byte-Stroms bei. Mehrere Frames aus einem feed() entstehen nach einer
            // Resynchronisation oder bei zusammengefassten Notifications - nebenlaeufige
            // Coroutinen koennten sie vertauscht in den SharedFlow schreiben.
            _frames.tryEmit(frame)
        }
        // Jede Notification aktualisiert die Kennzahl, nicht nur solche mit validem Frame -
        // sonst wuerde eine Serie reiner Decode-Fehler (kein einziges valides Frame) den
        // Supervisor nie erreichen (Plan Abschnitt 5.5).
        _frameQuality.value = FrameQuality(
            totalFrames = validFrameCount + decoder.decodeErrors,
            errorFrames = decoder.decodeErrors.toLong(),
        )
    }

    override suspend fun disconnect() {
        connecting?.complete(false)
        connecting = null
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (e: SecurityException) {
            Log.w(TAG, "Bluetooth-Berechtigung beim Trennen fehlt", e)
        }
        gatt = null
        _state.value = ConnectionState.DISCONNECTED
    }

    override suspend fun send(command: MeterCommand): Result<Unit> =
        Result.failure(
            UnsupportedOperationException(
                "Kommandokanal ${Pce323Profile.WRITE_CHARACTERISTIC_UUID} ist unbestaetigt (M0) - " +
                    "es wird kein Kommando gesendet, um die Verbindung nicht zu gefaehrden"
            )
        )
}
