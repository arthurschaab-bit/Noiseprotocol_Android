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

// v2: Owner-Rueckmeldung nach Geraetetest - im Klingelton-Modus "Lautlos" fiel der Alarm nicht
// auf, weil kein Vibrationsmuster gesetzt war. NotificationChannel-Einstellungen (Vibration,
// Sound, Importance) sind nach dem ersten createNotificationChannel() unveraenderlich - eine
// neue Channel-ID ist der einzige Weg, bestehende Installationen auf das neue Muster zu heben.
// Der alte Kanal wird beim naechsten [send] mitentfernt (siehe unten), damit er nicht als
// Karteileiche in den Systemeinstellungen haengen bleibt.
private const val ALARM_NOTIFICATION_CHANNEL_ID_LEGACY = "noise_alarm_channel"
const val ALARM_NOTIFICATION_CHANNEL_ID = "noise_alarm_channel_v2"
private const val ALARM_NOTIFICATION_ID = 4711

/**
 * Vibrationsmuster fuer den Verbindungsalarm: mindestens [mindestdauerMillis] durchgehendes
 * Vibrieren in kurzen Stoessen, damit der Alarm auch im Klingelton-Modus "Lautlos" auffaellt -
 * ein einzelner Standard-Kurzimpuls (Systemdefault) reicht dafuer nicht, wenn das Geraet nicht in
 * der Hand liegt. Reine Funktion ohne Android-Abhaengigkeit, deshalb per JVM-Test pruefbar.
 * Format wie bei [android.app.NotificationChannel.setVibrationPattern]: erster Wert ist die
 * Anfangsverzoegerung (hier 0), danach abwechselnd Vibrieren/Pause in Millisekunden.
 */
internal fun alarmVibrationsmuster(mindestdauerMillis: Long = 60_000L): LongArray {
    val vibrieren = 800L
    val pause = 400L
    val zyklusDauer = vibrieren + pause
    val anzahlZyklen = ((mindestdauerMillis + zyklusDauer - 1) / zyklusDauer).toInt()
    return LongArray(1 + anzahlZyklen * 2).also { muster ->
        for (i in 0 until anzahlZyklen) {
            muster[1 + i * 2] = vibrieren
            muster[2 + i * 2] = pause
        }
    }
}

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

        manager.deleteNotificationChannel(ALARM_NOTIFICATION_CHANNEL_ID_LEGACY)
        manager.createNotificationChannel(
            NotificationChannel(
                ALARM_NOTIFICATION_CHANNEL_ID,
                "Verbindungsalarm",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Meldet, wenn die Verbindung zum Messgerät abreißt."
                enableVibration(true)
                vibrationPattern = alarmVibrationsmuster()
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
