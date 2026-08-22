package com.example.lrmprotokoll.alert.local

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.lrmprotokoll.alert.Alert
import com.example.lrmprotokoll.alert.AlertChannel
import com.example.lrmprotokoll.alert.AlertKind
import com.example.lrmprotokoll.alert.AlertMessages
import com.example.lrmprotokoll.alert.ChannelId
import com.example.lrmprotokoll.data.SettingsManager

private const val ALARM_NOTIFICATION_CHANNEL_ID_V1 = "noise_alarm_channel"
private const val ALARM_NOTIFICATION_CHANNEL_ID_V2 = "noise_alarm_channel_v2"
const val ALARM_NOTIFICATION_CHANNEL_ID = "noise_alarm_channel_v3"
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
 * Gehärtet für Android Tablets (z.B. Xiaomi Pad 6 / HyperOS) und Telefone:
 * - Spielt bei Alarmen einen akustischen Alarmton mit USAGE_ALARM (auch bei stummem Gerät / Tablets ohne Motor).
 * - Prüft Hardware-Vibrationsmotor (`hasVibrator`) und triggert direkte Hardware-Vibration.
 * - Setzt den NotificationChannel mit explizitem Alarm-Sound und IMPORTANCE_HIGH.
 */
class LocalNotificationAlertChannel(
    private val context: Context,
    private val settings: SettingsManager? = null,
) : AlertChannel {

    override val id: ChannelId = ChannelId.LOCAL_NOTIFICATION

    override val isAvailable: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    companion object {
        @Volatile
        private var aktiverAlarmTon: Ringtone? = null

        fun stoppeAlarmTon() {
            try {
                aktiverAlarmTon?.stop()
                aktiverAlarmTon = null
            } catch (_: Exception) {}
        }
    }

    override suspend fun send(alert: Alert): Result<Unit> = runCatching {
        val manager = context.getSystemService(NotificationManager::class.java)
            ?: error("NotificationManager nicht verfügbar")

        // Alte Kanäle aufräumen
        try {
            manager.deleteNotificationChannel(ALARM_NOTIFICATION_CHANNEL_ID_V1)
            manager.deleteNotificationChannel(ALARM_NOTIFICATION_CHANNEL_ID_V2)
        } catch (_: Exception) {}

        val alarmSoundUri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: Uri.EMPTY

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        manager.createNotificationChannel(
            NotificationChannel(
                ALARM_NOTIFICATION_CHANNEL_ID,
                "Verbindungsalarm",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Meldet mit Alarmton und Vibration, wenn die Verbindung zum Messgerät abreißt."
                if (alarmSoundUri != Uri.EMPTY) {
                    setSound(alarmSoundUri, audioAttributes)
                }
                enableVibration(true)
                vibrationPattern = alarmVibrationsmuster()
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )

        val meldung = NotificationCompat.Builder(context, ALARM_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(AlertMessages.titel(alert.kind))
            .setContentText(alert.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.message))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .build()

        // Feste ID: Die Entwarnung ersetzt die stehende Alarmmeldung
        manager.notify(ALARM_NOTIFICATION_ID, meldung)

        // Akustischer Ton & Hardware-Vibration für Tablets & OEM Geräte
        when (alert.kind) {
            AlertKind.RAISED, AlertKind.ESCALATED, AlertKind.TEST -> {
                spieleAlarmTon(alarmSoundUri, audioAttributes)
                triggereHardwareVibration(audioAttributes)
            }
            AlertKind.RESOLVED -> {
                stoppeAlarmTon()
            }
        }
    }

    private fun spieleAlarmTon(soundUri: Uri, audioAttributes: AudioAttributes) {
        if (settings?.alarmTonAktiv == false) return
        try {
            stoppeAlarmTon()
            val ringtone = RingtoneManager.getRingtone(context, soundUri) ?: return
            ringtone.audioAttributes = audioAttributes
            ringtone.play()
            aktiverAlarmTon = ringtone
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun triggereHardwareVibration(audioAttributes: AudioAttributes) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (vibrator != null && vibrator.hasVibrator()) {
                val muster = alarmVibrationsmuster(mindestdauerMillis = 3_000L)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                        VibrationEffect.createWaveform(muster, -1),
                        audioAttributes
                    )
                } else {
                    vibrator.vibrate(muster, -1)
                }
            }
        } catch (_: Exception) {}
    }
}
