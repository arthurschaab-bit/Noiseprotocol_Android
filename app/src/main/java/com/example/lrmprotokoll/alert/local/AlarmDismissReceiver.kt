package com.example.lrmprotokoll.alert.local

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

const val ACTION_STOP_ALARM = "com.example.lrmprotokoll.ACTION_STOP_ALARM"

/**
 * Empfaengt Klicks auf die "Alarm stoppen"-Aktion oder das Wegwischen der Alarm-Notification
 * und beendet den akustischen Alarmton sofort.
 */
class AlarmDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        LocalNotificationAlertChannel.stoppeAlarmTon()
    }
}
