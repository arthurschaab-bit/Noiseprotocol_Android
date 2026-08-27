package com.example.lrmprotokoll.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.audio.AudioRecordingService
import com.example.lrmprotokoll.audio.EXTRA_START_AUDIO_MONITORING
import com.example.lrmprotokoll.ui.MainActivity
import java.util.Locale

private const val ACTION_TOGGLE = "com.example.lrmprotokoll.widget.ACTION_TOGGLE"

/**
 * F14: Homescreen-Widget mit Zustand und aktuellem Pegel - das Gegenstueck zum Quick Settings
 * Tile ([com.example.lrmprotokoll.service.NoiseMonitoringTileService]) fuer den Homescreen.
 * Klassisches `AppWidgetProvider`/`RemoteViews` statt androidx.glance (kein neuer Dependency,
 * derselbe minimale Abhaengigkeits-Stil wie bei den handgezeichneten Icons in `AppIcons.kt`,
 * Owner-Entscheidung M9).
 *
 * Anders als [android.service.quicksettings.TileService] hat ein `AppWidgetProvider` kein
 * "solange sichtbar"-Callback - RemoteViews werden stattdessen aktiv von aussen aktualisiert:
 * bei jedem echten Zustandswechsel (Start/Stopp) und zusaetzlich alle 5 Sekunden aus derselben
 * periodischen Schleife, die in [AudioRecordingService] ohnehin schon die Notification
 * aktualisiert (siehe die `updateAlleWidgets`-Aufrufe dort) - keine neue Alarm-/Timer-
 * Infrastruktur noetig. `updatePeriodMillis` in der Widget-Info-XML ist nur ein Sicherheitsnetz
 * (Systemminimum 30 Minuten), falls ein App-Prozesstod die aktiven Push-Updates unterbricht.
 */
class NoiseMonitoringWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = baueViews(context)
        appWidgetIds.forEach { id -> appWidgetManager.updateAppWidget(id, views) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_TOGGLE) return

        val service = Intent(context, AudioRecordingService::class.java)
        if (AudioRecordingService.laeuft.value) {
            service.action = "STOP_SERVICE"
            context.startService(service)
        } else {
            service.putExtra(EXTRA_START_AUDIO_MONITORING, true)
            context.startForegroundService(service)
        }
        // Optimistisches Sofort-Update, statt auf den naechsten periodischen Tick von
        // AudioRecordingService zu warten - sonst wirkt der Tippvorgang traege.
        updateAlleWidgets(context)
    }

    companion object {

        fun updateAlleWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NoiseMonitoringWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val views = baueViews(context)
            ids.forEach { id -> manager.updateAppWidget(id, views) }
        }

        private fun baueViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_noise_monitoring)
            val laeuft = AudioRecordingService.laeuft.value
            val audioAktiv = AudioRecordingService.audioAufnahmeAktiv.value
            val pegel = AudioRecordingService.currentMicDb.value

            views.setTextViewText(
                R.id.widget_status,
                context.getString(if (laeuft) R.string.widget_status_active else R.string.widget_status_inactive),
            )
            views.setTextColor(R.id.widget_status, if (laeuft) STATUS_COLOR_ACTIVE else STATUS_COLOR_INACTIVE)

            views.setTextViewText(
                R.id.widget_level,
                if (audioAktiv && pegel != null) String.format(Locale.getDefault(), "%.1f dB", pegel) else "--",
            )

            views.setTextViewText(
                R.id.widget_toggle_button,
                context.getString(if (laeuft) R.string.widget_action_stop else R.string.widget_action_start),
            )
            views.setInt(
                R.id.widget_toggle_button,
                "setBackgroundResource",
                if (laeuft) R.drawable.widget_button_active else R.drawable.widget_button_inactive,
            )

            val toggleIntent = Intent(context, NoiseMonitoringWidgetProvider::class.java).apply { action = ACTION_TOGGLE }
            views.setOnClickPendingIntent(
                R.id.widget_toggle_button,
                PendingIntent.getBroadcast(context, 0, toggleIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )

            val openAppIntent = Intent(context, MainActivity::class.java)
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(context, 0, openAppIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )

            return views
        }

        // Dieselben Farben wie das "Aktiv"-Badge in ProtokollScreen.kt (ModernSessionCard).
        private val STATUS_COLOR_ACTIVE = 0xFF15803D.toInt()
        private val STATUS_COLOR_INACTIVE = 0xFF6B7280.toInt()
    }
}
