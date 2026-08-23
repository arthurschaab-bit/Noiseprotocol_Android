package com.example.lrmprotokoll.meter.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Hilfsmethoden zur versionsabhängigen Prüfung und Anforderung von Bluetooth-Berechtigungen.
 *
 * Unterstützt Android 10/11 (API 29/30, Huawei P30 etc.) via ACCESS_FINE_LOCATION
 * sowie Android 12+ (API 31+) via BLUETOOTH_SCAN und BLUETOOTH_CONNECT.
 */
object BluetoothPermissions {

    /**
     * Liefert alle für den Bluetooth-Scan und die Kopplung notwendigen Laufzeitberechtigungen
     * abhängig von der Android-Version.
     */
    fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    /**
     * Prüft, ob alle für Bluetooth-Scan und -Verbindung erforderlichen Berechtigungen erteilt sind.
     */
    fun hasPermissions(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Prüft, ob die Verbindung zu einem Bluetooth-Gerät erlaubt ist.
     */
    fun hasConnectPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Unter Android 10/11 sind BLUETOOTH & BLUETOOTH_ADMIN im Manifest ausreichend
        }
    }

    /**
     * Prüft, ob der Bluetooth-Scan erlaubt ist.
     */
    fun hasScanPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }
}
