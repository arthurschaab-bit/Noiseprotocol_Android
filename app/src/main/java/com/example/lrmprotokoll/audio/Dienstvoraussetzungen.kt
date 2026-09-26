package com.example.lrmprotokoll.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.core.app.ActivityCompat
import com.example.lrmprotokoll.data.SettingsManager
import com.example.lrmprotokoll.meter.ble.BluetoothPermissions

/**
 * Welche Foreground-Service-Typen darf [AudioRecordingService] im aktuellen Zustand benutzen?
 *
 * `microphone` setzt die Mikrofonberechtigung voraus, `connectedDevice` ein gekoppeltes Messgeraet
 * samt `BLUETOOTH_CONNECT`. Die Bitmaske ist genau dann `0`, wenn **beides** fehlt.
 *
 * Bewusst als freie Funktion und nicht als Methode des Dienstes: die Aufrufer muessen dieselbe
 * Frage beantworten koennen, bevor sie den Dienst ueberhaupt starten (siehe
 * [kannDienstInDenVordergrund]).
 */
fun berechneForegroundServiceType(
    context: Context,
    settingsManager: SettingsManager,
): Int {
    val hatMikrofon =
        ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    val hatMessgeraet =
        BluetoothPermissions.hasConnectPermission(context) && settingsManager.meterDeviceAddress != null

    var typ = 0
    if (hatMikrofon) typ = typ or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    if (hatMessgeraet) typ = typ or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    return typ
}

/**
 * Kann [AudioRecordingService] ueberhaupt in den Vordergrund kommen?
 *
 * Ohne Mikrofonberechtigung **und** ohne gekoppeltes Messgeraet hat der Dienst nichts zu
 * ueberwachen. Er wuerde starten, keinen erlaubten Typ bekommen und sich sofort wieder beenden -
 * fuer den Nutzer ein stiller Fehlschlag (F-35 im UX/UI-Audit).
 *
 * Aufrufer ohne eigene Berechtigungspruefung fragen deshalb vorher hier nach, statt den Dienst ins
 * Leere zu starten: die Schnelleinstellungs-Kachel, das Homescreen-Widget und der Boot-Receiver.
 * Die Aufrufer in der App pruefen die Mikrofonberechtigung bereits selbst und kommen an dieser
 * Bedingung nicht vorbei.
 */
fun kannDienstInDenVordergrund(
    context: Context,
    settingsManager: SettingsManager,
): Boolean = berechneForegroundServiceType(context, settingsManager) != 0
