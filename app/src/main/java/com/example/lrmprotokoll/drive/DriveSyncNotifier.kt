package com.example.lrmprotokoll.drive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.example.lrmprotokoll.data.SettingsManager

private const val DRIVE_SYNC_NOTIFICATION_CHANNEL_ID = "drive_sync_channel"
private const val NOTIFICATION_ID_SYNC_FEHLER = 4712
private const val NOTIFICATION_ID_ORDNER_WEG = 4713

/** Ab wie vielen aufeinanderfolgenden gescheiterten Zyklen gewarnt wird - bei 30 min Takt sind
 * das rund 3 h (Plan 8.4.6, Default 6). */
private const val WARN_NACH_ZYKLEN = 6

/**
 * Meldet Sync-Probleme ueber einen EIGENEN, ruhigen Notification-Kanal - ausdruecklich nicht
 * ueber [com.example.lrmprotokoll.alert.local.LocalNotificationAlertChannel] aus M5.
 *
 * Plan 8.4.6: "Sync-Ausfall ist meldepflichtig, aber kein SMS-Fall ... SMS/Push bleiben dem
 * Verbindungsabbruch vorbehalten - sonst wird der Alarmkanal abgestumpft." Ein Sync-Problem ist
 * aergerlich, aber nicht das Gleiche wie ein Messgeraet, das nicht mehr erreichbar ist - beide
 * ueber denselben IMPORTANCE_HIGH-Alarmkanal zu melden wuerde beide gleich dringend wirken lassen
 * und den echten Alarm auf Dauer entwerten.
 */
class DriveSyncNotifier(private val context: Context) {

    private val manager: NotificationManager?
        get() = context.getSystemService(NotificationManager::class.java)

    private fun stelleKanalSicher() {
        manager?.createNotificationChannel(
            NotificationChannel(
                DRIVE_SYNC_NOTIFICATION_CHANNEL_ID,
                "Drive-Synchronisation",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Meldet, wenn der Upload der Pegelwerte zu Google Drive nicht funktioniert."
            }
        )
    }

    /** Nach [WARN_NACH_ZYKLEN] aufeinanderfolgenden Fehlschlaegen warnen, nicht bei jedem
     * einzelnen - sonst waere ein einzelner Netzausfall schon eine Meldung wert. */
    fun pruefeUndBenachrichtige(settings: SettingsManager) {
        if (settings.driveSyncFehlschlaegeInFolge < WARN_NACH_ZYKLEN) return
        stelleKanalSicher()

        val meldung = NotificationCompat.Builder(context, DRIVE_SYNC_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Drive-Synchronisation gestört")
            .setContentText(
                "Der Upload zu Google Drive schlägt seit ${settings.driveSyncFehlschlaegeInFolge} " +
                    "Zyklen fehl. Messwerte werden weiterhin lokal gespeichert."
            )
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        manager?.notify(NOTIFICATION_ID_SYNC_FEHLER, meldung)
    }

    fun ordnerNichtGefunden() {
        stelleKanalSicher()

        val meldung = NotificationCompat.Builder(context, DRIVE_SYNC_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Drive-Ordner nicht gefunden")
            .setContentText("Bitte in den Einstellungen einen neuen Ordner für die Synchronisation wählen.")
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        manager?.notify(NOTIFICATION_ID_ORDNER_WEG, meldung)
    }
}
