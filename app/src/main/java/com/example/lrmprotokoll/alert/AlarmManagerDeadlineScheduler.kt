package com.example.lrmprotokoll.alert

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.lrmprotokoll.LaermprotokollApp
import java.time.Instant

private const val TAG = "AlarmDeadline"
private const val REQUEST_CODE = 0xA1A2

/** Eigene Action statt eines impliziten Intents - so kann nur diese App den Weckruf ausloesen. */
internal const val ACTION_ALARM_DEADLINE = "com.example.lrmprotokoll.ALARM_DEADLINE"

/**
 * Weckt den [AlarmCoordinator] ueber den AlarmManager (Plan Abschnitt 7.2).
 *
 * `setExactAndAllowWhileIdle()` ist hier nicht Bequemlichkeit, sondern die Bedingung dafuer, dass
 * die Karenzzeit ueberhaupt etwas taugt: Ein Coroutine-`delay()` haelt sich im Doze-Modus nicht an
 * die Uhr und feuert unter Umstaenden Stunden spaeter - also genau dann nicht, wenn der Alarm
 * gebraucht wird.
 */
class AlarmManagerDeadlineScheduler(private val context: Context) : DeadlineScheduler {

    private val manager = context.getSystemService(AlarmManager::class.java)

    private val intent: PendingIntent
        get() = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(ACTION_ALARM_DEADLINE).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    override val kannExakt: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager?.canScheduleExactAlarms() == true
        } else {
            true
        }

    override fun schedule(at: Instant) {
        val am = manager ?: return
        if (kannExakt) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), intent)
        } else {
            // Ohne die Berechtigung ist ein exakter Alarm nicht zu haben. Statt still auf einen
            // ungenauen zurueckzufallen und den Nutzer im Glauben zu lassen, die Karenzzeit
            // stimme, wird der Rueckfall protokolliert - sichtbar wird er ueber [kannExakt] im
            // Einstellungsbildschirm.
            Log.w(TAG, "Keine Berechtigung für exakte Alarme - Karenzzeit kann im Doze verspätet feuern")
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), intent)
        }
    }

    override fun cancel() {
        manager?.cancel(intent)
    }
}

/** Nimmt den Weckruf entgegen und reicht ihn an den Koordinator weiter. */
class AlarmDeadlineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ALARM_DEADLINE) return
        val container = (context.applicationContext as? LaermprotokollApp)?.container ?: return
        container.alarmCoordinator.onDeadline()
    }
}
