package com.example.lrmprotokoll.diagnose

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager

/**
 * Uebersetzt [Intent.ACTION_BATTERY_CHANGED] in einen [Stromzustand].
 */
fun parseStromzustand(intent: Intent): Stromzustand {
    val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
    val quelle =
        when {
            plugged == BatteryManager.BATTERY_PLUGGED_AC -> Stromzustand.Quelle.NETZTEIL
            plugged == BatteryManager.BATTERY_PLUGGED_USB -> Stromzustand.Quelle.USB
            plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS -> Stromzustand.Quelle.KABELLOS
            plugged == BatteryManager.BATTERY_PLUGGED_DOCK -> Stromzustand.Quelle.SONSTIGE
            plugged != 0 -> Stromzustand.Quelle.SONSTIGE
            else -> Stromzustand.Quelle.KEINE
        }

    val statusInt = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
    val status =
        when (statusInt) {
            BatteryManager.BATTERY_STATUS_CHARGING -> Stromzustand.Status.LAEDT
            BatteryManager.BATTERY_STATUS_FULL -> Stromzustand.Status.VOLL
            BatteryManager.BATTERY_STATUS_DISCHARGING -> Stromzustand.Status.ENTLAEDT
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> Stromzustand.Status.ANGESCHLOSSEN_LAEDT_NICHT
            else -> Stromzustand.Status.UNBEKANNT
        }

    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    val prozent = if (level >= 0 && scale > 0) {
        (level * 100) / scale
    } else {
        null
    }

    return Stromzustand(quelle = quelle, status = status, prozent = prozent)
}

/**
 * Dynamischer BroadcastReceiver fuer [Intent.ACTION_BATTERY_CHANGED] im laufenden Dienst
 * (docs/PROMPT_FIX_LADEZUSTAND_PROTOKOLLIEREN.md Schritt 2).
 */
class StromversorgungEmpfaenger(
    private val onEintrag: (String) -> Unit,
) : BroadcastReceiver() {

    internal var letzterZustand: Stromzustand? = null

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_BATTERY_CHANGED) return

        val jetzt = parseStromzustand(intent)
        val vorher = letzterZustand
        val eintrag = naechsterEintrag(vorher, jetzt)
        letzterZustand = jetzt

        if (eintrag != null) {
            onEintrag(eintrag)
        }
    }
}
