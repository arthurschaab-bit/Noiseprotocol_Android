package com.example.lrmprotokoll.diagnose.export

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.diagnose.acra.SUPPORT_OUTBOX_DIR
import com.example.lrmprotokoll.drive.DriveApiClient
import com.example.lrmprotokoll.drive.DriveOrdnerbaum
import java.io.File
import java.util.concurrent.TimeUnit

private const val TAG = "SupportBundleUpload"

private const val OUTBOX_MAX_DATEIEN = 20
private const val OUTBOX_MAX_GESAMT_BYTES = 100L * 1024 * 1024

/**
 * Raeumt `support_outbox/` auf eine Obergrenze (Anzahl und Gesamtgroesse) ab (M12 Schritt 5
 * Aufgabe 4). Periodische/manuelle Bundles werden zuerst verworfen, Absturz-/ANR-Bundles
 * zuletzt - am Dateinamen erkennbar (Konzept 4.6: `..._absturz.zip`/`..._anr.zip`).
 */
fun raeumeSupportOutboxAuf(outboxDir: File) {
    val dateien = outboxDir.listFiles { f -> f.isFile && f.name.endsWith(".zip") }?.toList().orEmpty()
    if (dateien.isEmpty()) return

    fun prioritaet(name: String) = if (name.contains("_absturz") || name.contains("_anr")) 1 else 0
    val sortiert = dateien.sortedWith(compareBy({ prioritaet(it.name) }, { it.lastModified() }))

    var gesamtGroesse = sortiert.sumOf { it.length() }
    var index = 0
    while (index < sortiert.size && (sortiert.size - index > OUTBOX_MAX_DATEIEN || gesamtGroesse > OUTBOX_MAX_GESAMT_BYTES)) {
        val datei = sortiert[index]
        gesamtGroesse -= datei.length()
        datei.delete()
        index++
    }
}

/**
 * M12 Schritt 5 (Konzept 4.2, 4.7 - behebt Luecke L6): laedt Bundles aus `support_outbox/` nach
 * `<Wurzel>/Support-Bundle` hoch und loescht sie bei Erfolg. Laeuft im Hauptprozess (nicht im
 * ACRA-Sender-Prozess) - dort liegen Keystore, OAuth-Token und [DriveApiClient] (Konzept 4.2:
 * `EncryptedSharedPreferences` ist prozessuebergreifend nicht konsistent).
 *
 * Derselbe Worker bedient alle vier Bundle-Typen (Absturz, ANR, periodisch, manuell) - der
 * ACRA-Sender (Schritt 5) und der periodische Worker (Schritt 6) reihen beide nur eine Datei in
 * die Outbox und rufen [SupportBundleUploadPlanung] auf.
 */
class SupportBundleUploadWorker @JvmOverloads constructor(
    context: Context,
    parameter: WorkerParameters,
    /** Testseam wie bei [com.example.lrmprotokoll.drive.DriveSyncWorker]: `null` verwendet den
     * echten Client aus dem Container. */
    private val driveApiClientOverride: DriveApiClient? = null,
) : CoroutineWorker(context, parameter) {

    override suspend fun doWork(): Result {
        val outboxDir = File(applicationContext.filesDir, SUPPORT_OUTBOX_DIR).apply { mkdirs() }
        raeumeSupportOutboxAuf(outboxDir)

        val dateien = outboxDir.listFiles { f -> f.isFile && f.name.endsWith(".zip") }
            ?.sortedBy { it.lastModified() }
            .orEmpty()
        if (dateien.isEmpty()) return Result.success()

        val container = (applicationContext as LaermprotokollApp).container
        val settings = container.settingsManager
        val driveApi = driveApiClientOverride ?: container.driveApiClient
        val ordnerbaum = DriveOrdnerbaum(driveApi)

        val wurzelId = settings.driveFolderId
        if (wurzelId == null) {
            // Kein Drive-Ordner eingerichtet - die Bundles bleiben in der Outbox liegen, bis
            // der Nutzer einen Ordner waehlt. Kein Fehler, kein Retry-Sturm.
            return Result.success()
        }

        val supportOrdnerId = ordnerbaum.ordnerFuerSupportBundles(wurzelId).getOrElse {
            Log.w(TAG, "Support-Ordner nicht erreichbar", it)
            settings.supportBundleLastUploadMessage = "Fehlgeschlagen: Support-Ordner nicht erreichbar (${it.message})"
            return Result.retry()
        }

        var alleErfolgreich = true
        for (datei in dateien) {
            val ergebnis = runCatching {
                // Dedup (Akzeptanzkriterium "nichts wird doppelt hochgeladen"): ueberlebt ein
                // frueherer Durchlauf den Upload, aber nicht das anschliessende Loeschen (z.B.
                // Prozess wurde dazwischen beendet), waere die Datei sonst ein zweites Mal
                // hochgeladen worden.
                val vorhanden = driveApi.dateiSuchen(datei.name, supportOrdnerId).getOrThrow()
                if (vorhanden != null) {
                    datei.delete()
                    return@runCatching
                }
                driveApi.dateiHochladenResumable(
                    name = datei.name,
                    ordnerId = supportOrdnerId,
                    datei = datei,
                    mimeType = "application/zip",
                ).getOrThrow()
                datei.delete()
            }
            if (ergebnis.isSuccess) {
                settings.supportBundleLastUploadAt = System.currentTimeMillis()
                settings.supportBundleLastUploadMessage = "Erfolgreich: ${datei.name}"
            } else {
                alleErfolgreich = false
                Log.w(TAG, "Upload fehlgeschlagen: ${datei.name}", ergebnis.exceptionOrNull())
                settings.supportBundleLastUploadMessage = "Fehlgeschlagen: ${datei.name} (${ergebnis.exceptionOrNull()?.message})"
            }
        }

        return if (alleErfolgreich) Result.success() else Result.retry()
    }
}

/**
 * WorkManager-Glue fuer [SupportBundleUploadWorker] (Konzept 4.7 - Upload-Ausloeser).
 */
object SupportBundleUploadPlanung {

    private const val WORK_NAME = "support_bundle_upload"
    private const val WORK_NAME_FALLBACK = "support_bundle_upload_fallback"

    /** UNMETERED bevorzugt - sofort eingereiht, laeuft aber erst, wenn WLAN da ist. */
    fun planeSofort(context: Context) {
        try {
            val anfrage = OneTimeWorkRequestBuilder<SupportBundleUploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, anfrage)
        } catch (e: Throwable) {
            Log.w(TAG, "WorkManager konnte nicht aufgerufen werden", e)
        }
    }

    /**
     * Fallback fuer ein Absturz-/ANR-Bundle, das laenger als 6h auf WLAN wartet (Konzept 4.7):
     * ein Geraet, das wochenlang ohne WLAN Dauerueberwachung faehrt, darf einen Absturzbericht
     * nicht unbegrenzt zurueckhalten. Nur vom Absturz-/ANR-Pfad aufgerufen - periodische Bundles
     * (Schritt 6) sind nichts, wofuer Mobilfunkvolumen draufgehen soll.
     */
    fun planeFallbackOhneNetzbeschraenkung(context: Context) {
        try {
            val anfrage = OneTimeWorkRequestBuilder<SupportBundleUploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(6, TimeUnit.HOURS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME_FALLBACK, ExistingWorkPolicy.KEEP, anfrage)
        } catch (e: Throwable) {
            Log.w(TAG, "WorkManager konnte nicht aufgerufen werden", e)
        }
    }
}
