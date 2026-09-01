package com.example.lrmprotokoll.alert.heartbeat

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.lrmprotokoll.LaermprotokollApp
import java.util.concurrent.TimeUnit

private const val WORK_NAME = "heartbeat"

/**
 * Sendet das Lebenszeichen der Totmannschaltung periodisch (Plan Abschnitt 7.5).
 *
 * **Zum Intervall:** Der Plan nennt 5 Minuten, merkt aber selbst an, dass WorkManagers
 * Mindestintervall bei 15 Minuten liegt und fuer 5 Minuten ein zusaetzlicher
 * AlarmManager-Takt noetig waere. Hier laeuft es mit 15 Minuten ohne Zusatztakt: Zwei
 * konkurrierende Zeitgeber fuer ein Lebenszeichen sind mehr Risiko als Gewinn, und das
 * Ueberwachungsfenster auf der Gegenseite (Vorschlag: 45 min) faengt den groeberen Takt auf.
 * Die Abweichung ist im PR vermerkt.
 */
class HeartbeatWorker @JvmOverloads constructor(
    context: Context,
    parameter: WorkerParameters,
    /** Testluecken-Auftrag Stufe 2: Testseam, `null` (Standard) verwendet den echten Pinger aus
     * dem Container. Ein Test uebergibt hier einen [HeartbeatPinger] gegen einen MockWebServer,
     * ueber eine eigene WorkerFactory. */
    private val pingerOverride: HeartbeatPinger? = null,
) : CoroutineWorker(context, parameter) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as LaermprotokollApp).container
        val pinger = pingerOverride ?: container.heartbeatPinger
        return when (val ergebnis = pinger.ping()) {
            HeartbeatPinger.Ergebnis.GESENDET -> {
                container.diagnosticsReporter.breadcrumb("Heartbeat", "Heartbeat erfolgreich gesendet")
                Result.success()
            }
            HeartbeatPinger.Ergebnis.UEBERSPRUNGEN -> Result.success()

            // retry() statt failure(): Ein einzelner misslungener Ping ist meistens ein
            // kurzer Netzausfall. Waere das ein endgueltiger Fehlschlag, bliebe die
            // Totmannschaltung bis zum naechsten regulaeren Lauf stumm.
            HeartbeatPinger.Ergebnis.FEHLGESCHLAGEN -> {
                container.diagnosticsReporter.report(
                    code = com.example.lrmprotokoll.diagnose.DiagnosticCode.HEARTBEAT_SEND_FAILED,
                    component = "HeartbeatWorker",
                    operation = "doWork",
                    severity = com.example.lrmprotokoll.diagnose.DiagnosticSeverity.WARN,
                    message = "Heartbeat-Ping fehlgeschlagen"
                )
                Result.retry()
            }
        }
    }
}

object HeartbeatPlanung {

    fun plane(context: Context) {
        try {
            val anfrage = PeriodicWorkRequestBuilder<HeartbeatWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                // UPDATE statt KEEP: Sonst bliebe nach einer Aenderung an der Konfiguration der alte
                // Auftrag stehen, und der Nutzer haette keine Moeglichkeit zu erkennen, dass seine
                // Aenderung nicht greift.
                ExistingPeriodicWorkPolicy.UPDATE,
                anfrage,
            )
        } catch (e: Throwable) {
            android.util.Log.w("HeartbeatPlanung", "WorkManager konnte nicht aufgerufen werden", e)
        }
    }

    fun stoppe(context: Context) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        } catch (e: Throwable) {
            android.util.Log.w("HeartbeatPlanung", "WorkManager konnte nicht aufgerufen werden", e)
        }
    }
}
