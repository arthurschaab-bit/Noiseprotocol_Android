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

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder

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
        container.diagnosticsReporter.breadcrumb("DriveSync", "Drive-Sync-Zyklus gestartet")
        return when (val ergebnis = container.driveSyncCoordinator.syncEinenZyklus()) {
            is DriveSyncCoordinator.SyncErgebnis.Erfolgreich -> {
                container.diagnosticsReporter.breadcrumb("DriveSync", "Drive-Sync erfolgreich: ${ergebnis.zeilen} Zeilen")
                DriveSyncNotifier(applicationContext).pruefeUndBenachrichtige(container.settingsManager)
                Result.success()
            }
            is DriveSyncCoordinator.SyncErgebnis.KeineAenderung,
            is DriveSyncCoordinator.SyncErgebnis.SyncAusgeschaltet,
            is DriveSyncCoordinator.SyncErgebnis.KeinOrdnerEingerichtet,
            is DriveSyncCoordinator.SyncErgebnis.OrdnerBlockiert -> {
                DriveSyncNotifier(applicationContext).pruefeUndBenachrichtige(container.settingsManager)
                Result.success()
            }

            is DriveSyncCoordinator.SyncErgebnis.OrdnerNichtGefunden -> {
                container.diagnosticsReporter.report(
                    code = com.example.lrmprotokoll.diagnose.DiagnosticCode.DRIVE_FOLDER_NOT_FOUND,
                    component = "DriveSyncWorker",
                    operation = "doWork",
                    severity = com.example.lrmprotokoll.diagnose.DiagnosticSeverity.WARN,
                    message = "Drive-Ordner nicht gefunden (HTTP ${ergebnis.httpCode})",
                    details = mapOf("httpCode" to (ergebnis.httpCode ?: -1))
                )
                DriveSyncNotifier(applicationContext).ordnerNichtGefunden()
                Result.failure()
            }

            // Plan 8.4.6: kein Netz / 403 Quota -> Result.retry() mit WorkManager-Backoff.
            // 401 wird hier NICHT gesondert behandelt: der AccessTokenProvider fordert vor
            // jedem Zyklus ohnehin frisch an (siehe AccessTokenProvider-KDoc), ein 401 loest
            // sich damit im naechsten Zyklus von selbst, wenn die Zustimmung noch besteht. Bleibt
            // es bestehen, greift wie bei jedem anderen Fehlschlag die Warnung nach n Zyklen.
            is DriveSyncCoordinator.SyncErgebnis.Fehlgeschlagen -> {
                container.diagnosticsReporter.report(
                    code = com.example.lrmprotokoll.diagnose.DiagnosticCode.DRIVE_SYNC_FAILED,
                    component = "DriveSyncWorker",
                    operation = "doWork",
                    severity = com.example.lrmprotokoll.diagnose.DiagnosticSeverity.WARN,
                    message = "Drive-Sync fehlgeschlagen: ${ergebnis.grund} (HTTP ${ergebnis.httpCode})",
                    details = mapOf("grund" to ergebnis.grund, "httpCode" to (ergebnis.httpCode ?: -1))
                )
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

        try {
            val anfrage = PeriodicWorkRequestBuilder<DriveSyncWorker>(30, TimeUnit.MINUTES)
                .setConstraints(einschraenkungen)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                anfrage,
            )
        } catch (e: Throwable) {
            android.util.Log.w("DriveSyncPlanung", "WorkManager konnte nicht aufgerufen werden", e)
        }
    }

    /**
     * Startet sofort einen einmaligen Synchronisationslauf (z.B. nach einer WAV-Aufnahme).
     */
    fun starteSofort(context: Context) {
        try {
            val einschraenkungen = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val anfrage = OneTimeWorkRequestBuilder<DriveSyncWorker>()
                .setConstraints(einschraenkungen)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "${WORK_NAME}_immediate",
                ExistingWorkPolicy.REPLACE,
                anfrage,
            )
        } catch (e: Throwable) {
            android.util.Log.w("DriveSyncPlanung", "WorkManager konnte nicht aufgerufen werden", e)
        }
    }

    fun stoppe(context: Context) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        } catch (e: Throwable) {
            android.util.Log.w("DriveSyncPlanung", "WorkManager konnte nicht aufgerufen werden", e)
        }
    }
}
