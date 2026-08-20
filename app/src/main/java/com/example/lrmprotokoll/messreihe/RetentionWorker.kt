package com.example.lrmprotokoll.messreihe

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.lrmprotokoll.LaermprotokollApp
import java.util.concurrent.TimeUnit

private const val WORK_NAME = "retention"

/**
 * Der taegliche Retention-Job (Plan Abschnitt 8.2/13.2). Reine WorkManager-Glue - die
 * Entscheidungslogik steht in [RetentionCoordinator].
 */
class RetentionWorker(
    context: Context,
    parameter: WorkerParameters,
) : CoroutineWorker(context, parameter) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as LaermprotokollApp).container
        return runCatching { container.retentionCoordinator.verdichte() }
            .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }
}

object RetentionPlanung {

    /**
     * Wird unabhaengig davon geplant, ob gerade eine Ueberwachung laeuft (anders als
     * Heartbeat/Drive-Sync) - alte Rohwerte sollen auch verdichtet werden, wenn der Nutzer die
     * App laengere Zeit nicht geoeffnet hat. `KEEP` statt `UPDATE`: die Konfiguration aendert
     * sich nie zur Laufzeit, ein taeglich neu aufgesetzter Auftrag braechte nichts.
     */
    fun plane(context: Context) {
        val anfrage = PeriodicWorkRequestBuilder<RetentionWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            anfrage,
        )
    }
}
