package com.example.lrmprotokoll.alert.local

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.lrmprotokoll.alert.Alert
import com.example.lrmprotokoll.alert.AlertChannel
import com.example.lrmprotokoll.alert.AlertMessages
import com.example.lrmprotokoll.alert.ChannelId

const val ALARM_NOTIFICATION_CHANNEL_ID = "noise_alarm_channel"
private const val ALARM_NOTIFICATION_ID = 4711

/**
 * Meldung auf dem Ueberwachungsgeraet selbst (Plan 7.6, `LocalNotificationAlertChannel`).
 *
 * Sie ersetzt keinen Push und soll es nicht: Wer nicht am Geraet ist, sieht sie nicht. Sie deckt
 * aber einen Ausfall ab, an dem ntfy scheitert - naemlich fehlendes Internet - und kostet dafuer
 * weder Netz noch Fremddienst. Genau darum gehen die Kanaele parallel raus und nicht als Kette.
 *
 * Eigener Notification-Kanal mit IMPORTANCE_HIGH, getrennt vom stillen Kanal des Foreground
 * Service: Der laeuft bewusst mit IMPORTANCE_LOW, damit die Dauer-Notification nicht nervt - ein
 * Alarm auf demselben Kanal waere damit ebenso lautlos und niemandem aufgefallen.
 */
class LocalNotificationAlertChannel(private val context: Context) : AlertChannel {

    override val id: ChannelId = ChannelId.LOCAL_NOTIFICATION

    override val isAvailable: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    override suspend fun send(alert: Alert): Result<Unit> = runCatching {
        val manager = context.getSystemService(NotificationManager::class.java)
            ?: error("NotificationManager nicht verfügbar")

        manager.createNotificationChannel(
            NotificationChannel(
                ALARM_NOTIFICATION_CHANNEL_ID,
                "Verbindungsalarm",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Meldet, wenn die Verbindung zum Messgerät abreißt."
            }
        )

        val meldung = NotificationCompat.Builder(context, ALARM_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(AlertMessages.titel(alert.kind))
            .setContentText(alert.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.message))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        // Feste ID: Die Entwarnung ersetzt die stehende Alarmmeldung, statt sich daneben zu
        // stapeln - sonst stuenden am Morgen Alarm und Entwarnung nebeneinander und man muesste
        // die Zeitstempel vergleichen, um zu wissen, was gerade gilt.
        manager.notify(ALARM_NOTIFICATION_ID, meldung)
    }
}
