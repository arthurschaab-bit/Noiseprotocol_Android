package com.example.lrmprotokoll.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.lrmprotokoll.LaermprotokollApp

/**
 * Nimmt die Ueberwachung nach einem Geraeteneustart automatisch wieder auf, falls sie beim
 * letzten expliziten Stop noch aktiv war (Plan Abschnitt 5.4) - sonst bliebe eine per
 * Reconnect-Backoff eigentlich robuste Verbindung nach jedem Neustart abgeschaltet, bis der
 * Nutzer die App von Hand oeffnet. [AudioRecordingService] pflegt die beiden Flags selbst
 * (gesetzt beim Start, zurueckgesetzt bei explizitem Stop - ein einfacher Prozess-/Geraetetod
 * ohne expliziten Stop laesst sie bewusst unveraendert).
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val settings = (context.applicationContext as LaermprotokollApp).container.settingsManager
        if (!settings.monitoringWasActive) return

        val serviceIntent = Intent(context, AudioRecordingService::class.java).apply {
            putExtra(EXTRA_START_AUDIO_MONITORING, settings.audioMonitoringWasActive)
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
