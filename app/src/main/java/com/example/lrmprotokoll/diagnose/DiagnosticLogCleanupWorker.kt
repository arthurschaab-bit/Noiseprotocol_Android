package com.example.lrmprotokoll.diagnose

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.lrmprotokoll.LaermprotokollApp
import java.util.concurrent.TimeUnit

private const val WORK_NAME = "diagnostic_log_cleanup"

/**
 * Der taegliche Bereinigungs-Job fuer das Diagnose-Log (Plan Abschnitt 6). Reine WorkManager-Glue
 * - die Entscheidungslogik steht in [DiagnosticLogCleanupCoordinator].
 */
class DiagnosticLogCleanupWorker(
    context: Context,
    parameter: WorkerParameters,
) : CoroutineWorker(context, parameter) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as LaermprotokollApp).container
        return runCatching { container.diagnosticLogCleanupCoordinator.bereinige() }
            .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }
}

object DiagnosticLogCleanupPlanung {

    /**
     * Anders als [com.example.lrmprotokoll.messreihe.RetentionPlanung] (immer geplant) NUR
     * gestartet, wenn das Diagnose-Log ueberhaupt eingeschaltet ist (Plan Abschnitt 6:
     * "standardmaessig aus") - ohne aktives Log gibt es nichts zu bereinigen, ein taeglich
     * unnoetig laufender Job waere Verschwendung.
     */
    fun plane(context: Context) {
        try {
            val anfrage = PeriodicWorkRequestBuilder<DiagnosticLogCleanupWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                anfrage,
            )
        } catch (e: Throwable) {
            android.util.Log.w("DiagnosticLogCleanupPlanung", "WorkManager konnte nicht aufgerufen werden", e)
        }
    }

    fun stoppe(context: Context) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        } catch (e: Throwable) {
            android.util.Log.w("DiagnosticLogCleanupPlanung", "WorkManager konnte nicht aufgerufen werden", e)
        }
    }
}
