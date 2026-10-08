package com.example.lrmprotokoll.audio

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.lrmprotokoll.ui.MainActivity

const val WAECHTER_NOTIFICATION_CHANNEL_ID = "aufzeichnungs_waechter"
const val WAECHTER_NOTIFICATION_ID = 4715

/**
 * Erzeugt lokale Benachrichtigungen des Aufzeichnungs-Waechters, wenn die Aufzeichnung auf neueren
 * Android-Versionen (ab SDK 30) oder nach Greifen des Schleifenschutzes nicht direkt aus dem
 * Hintergrund fortgesetzt werden darf (Plan 5.4, Befund G).
 */
open class AufzeichnungsWaechterNotifier(
    private val context: Context,
) {
    private val manager: NotificationManager?
        get() = context.getSystemService(NotificationManager::class.java)

    private fun stelleKanalSicher() {
        manager?.createNotificationChannel(
            NotificationChannel(
                WAECHTER_NOTIFICATION_CHANNEL_ID,
                "Aufzeichnungs-Wächter",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Informiert, wenn eine unerwartet beendete Aufzeichnung manuell bestaetigt werden muss."
            },
        )
    }

    /**
     * Sendet die Benachrichtigung zum Antippen und Fortsetzen.
     *
     * @return true wenn die Benachrichtigung abgesendet wurde, false wenn keine Berechtigung vorlag.
     */
    open fun benachrichtigeZumFortsetzen(
        titel: String = "Lärmprotokoll",
        nachricht: String = "Aufzeichnung wurde vom System beendet – tippen zum Fortsetzen",
    ): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        stelleKanalSicher()

        val intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        val pendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val meldung =
            NotificationCompat.Builder(context, WAECHTER_NOTIFICATION_CHANNEL_ID)
                .setContentTitle(titel)
                .setContentText(nachricht)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

        manager?.notify(WAECHTER_NOTIFICATION_ID, meldung)
        return true
    }
}
