package com.example.lrmprotokoll.drive

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.lrmprotokoll.LaermprotokollApp
import java.util.concurrent.TimeUnit

private const val WORK_NAME = "drive_sync"

/**
 * Der periodische Sync-Auftrag (Plan Abschnitt 8.4.5). Reine WorkManager-Glue - die
 * Entscheidungslogik steht in [DriveSyncCoordinator] und ist ohne diese Klasse testbar.
 *
 * `PeriodicWorkRequest` ist bewusst NICHT exakt getaktet, anders als die Karenzzeit in M5:
 * Bei Netzverlust waere ein exakter Wecker ohnehin nutzlos, und die Datenlage auf dem Geraet
 * bleibt vollstaendig (Plan 8.4.5) - Puenktlichkeit ist hier keine Anforderung.
 */
class DriveSyncWorker(
    context: Context,
    parameter: WorkerParameters,
) : CoroutineWorker(context, parameter) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as LaermprotokollApp).container
        return when (val ergebnis = container.driveSyncCoordinator.syncEinenZyklus()) {
            is DriveSyncCoordinator.SyncErgebnis.Erfolgreich,
            DriveSyncCoordinator.SyncErgebnis.KeineAenderung,
            DriveSyncCoordinator.SyncErgebnis.SyncAusgeschaltet,
            DriveSyncCoordinator.SyncErgebnis.KeinOrdnerEingerichtet,
            DriveSyncCoordinator.SyncErgebnis.OrdnerBlockiert -> {
                DriveSyncNotifier(applicationContext).pruefeUndBenachrichtige(container.settingsManager)
                Result.success()
            }

            is DriveSyncCoordinator.SyncErgebnis.OrdnerNichtGefunden -> {
                DriveSyncNotifier(applicationContext).ordnerNichtGefunden()
                Result.failure()
            }

            // Plan 8.4.6: kein Netz / 403 Quota -> Result.retry() mit WorkManager-Backoff.
            // 401 wird hier NICHT gesondert behandelt: der AccessTokenProvider fordert vor
            // jedem Zyklus ohnehin frisch an (siehe AccessTokenProvider-KDoc), ein 401 loest
            // sich damit im naechsten Zyklus von selbst, wenn die Zustimmung noch besteht. Bleibt
            // es bestehen, greift wie bei jedem anderen Fehlschlag die Warnung nach n Zyklen.
            is DriveSyncCoordinator.SyncErgebnis.Fehlgeschlagen -> {
                DriveSyncNotifier(applicationContext).pruefeUndBenachrichtige(container.settingsManager)
                Result.retry()
            }
        }
    }
}

object DriveSyncPlanung {

    fun plane(context: Context) {
        val einschraenkungen = Constraints.Builder()
            .setRequiredNetworkType(
                if ((context.applicationContext as LaermprotokollApp).container.settingsManager.driveWlanOnly) {
                    NetworkType.UNMETERED
                } else {
                    NetworkType.CONNECTED
                }
            )
            .build()

        val anfrage = PeriodicWorkRequestBuilder<DriveSyncWorker>(30, TimeUnit.MINUTES)
            .setConstraints(einschraenkungen)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            anfrage,
        )
    }

    fun stoppe(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
