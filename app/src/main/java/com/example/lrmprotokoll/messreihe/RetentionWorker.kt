package com.example.lrmprotokoll.messreihe

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.lrmprotokoll.LaermprotokollApp
import java.io.File
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
        val settings = container.settingsManager
        val db = container.database
        val dao = db.noiseDao()

        return runCatching {
            // 1. Messreihen-Verdichtung (M4)
            container.retentionCoordinator.verdichte()

            // 2. F5: Automatische Aufbewahrungs-Bereinigung (Favoriten bleiben geschützt)
            if (settings.autoRetentionEnabled && settings.autoRetentionDays > 0) {
                val cutoff = System.currentTimeMillis() - (settings.autoRetentionDays * 24L * 60 * 60 * 1000)
                val candidates = dao.getAutoRetentionCandidates(cutoff)
                if (candidates.isNotEmpty()) {
                    dao.softDeleteMultiple(candidates.map { it.id })
                }
            }

            // 3. F9: Papierkorb älter als 30 Tage endgültig löschen (inkl. WAV-Dateien)
            val trashCutoff = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
            val toDelete = dao.getTrashAelterAls(trashCutoff)
            toDelete.forEach { record ->
                val f = File(record.filePath)
                if (f.exists()) f.delete()
            }
            dao.deleteTrashAelterAls(trashCutoff)
            Unit
        }.fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
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
        try {
            val anfrage = PeriodicWorkRequestBuilder<RetentionWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                anfrage,
            )
        } catch (e: Throwable) {
            android.util.Log.w("RetentionPlanung", "WorkManager konnte nicht aufgerufen werden", e)
        }
    }
}
