package com.example.lrmprotokoll.drive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.backup.SicherungManager
import com.example.lrmprotokoll.backup.Vollsicherung
import com.example.lrmprotokoll.diagnose.DiagnosticCode
import com.example.lrmprotokoll.diagnose.DiagnosticSeverity
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

private const val TAG = "VollsicherungWorker"
private const val KANAL_ID = "datenbank_sicherung_channel"
private const val NOTIFICATION_ID = 4721
private const val NACHT_WORK_NAME = "datenbank_vollsicherung"
private const val ERST_WORK_NAME = "datenbank_vollsicherung_erst"

/** Owner-Entscheidung 10.10.2026: Vollsicherung einmal täglich um 03:00. */
private val VOLLSICHERUNG_UHRZEIT: LocalTime = LocalTime.of(3, 0)

/**
 * Nächtliche Vollsicherung der Datenbank nach Drive (Owner-Entscheidung 10.10.2026,
 * docs/PROMPT_SICHERUNG_VOLL_UND_TEIL.md): einmal am Tag um 03:00, nur im WLAN, ohne
 * `level_samples`. Läuft als Vordergrundarbeit, weil ein Upload von mehreren hundert MB die
 * 10-Minuten-Grenze gewöhnlicher Hintergrundarbeit überschreiten kann (vermutete Ursache der
 * „Job was cancelled“-Fehlschläge, Befund 2 in docs/BEFUNDE_BUNDLES_2026-10-10.md).
 *
 * Erst nach erfolgreichem Upload wird der Stand gemerkt, auf dem die 30-Minuten-Teilsicherungen
 * des Drive-Syncs aufbauen ([com.example.lrmprotokoll.data.SettingsManager.merkeVollsicherung]).
 */
class DatenbankVollsicherungWorker
    @JvmOverloads
    constructor(
        context: Context,
        parameter: WorkerParameters,
        /** Testseam: baut die Vollsicherung; `null` (Standard) nutzt [SicherungManager.baueVollsicherung]. */
        private val bauenOverride: (suspend () -> Vollsicherung)? = null,
        /** Testseam: `null` (Standard) nutzt den Drive-Client aus dem Container. */
        private val driveApiOverride: DriveApiClient? = null,
    ) : CoroutineWorker(context, parameter) {
        override suspend fun doWork(): Result {
            val container = (applicationContext as LaermprotokollApp).container
            val settings = container.settingsManager
            val ordnerId = settings.driveFolderId
            if (!settings.driveSyncEnabled || !settings.datenbankSicherungDriveUpload || ordnerId.isNullOrBlank()) {
                return Result.success()
            }
            runCatching { setForeground(vordergrundInfo()) }
                .onFailure { Log.w(TAG, "Vordergrund nicht moeglich, laufe im Hintergrund weiter", it) }

            val vollsicherung =
                try {
                    bauenOverride?.invoke() ?: SicherungManager.baueVollsicherung(applicationContext, settings)
                } catch (e: CancellationException) {
                    throw e
                } catch (fehler: Throwable) {
                    melde(container, "bauen", fehler)
                    return Result.retry()
                }
            try {
                return DriveDatenbankSicherung
                    .hochladen(driveApiOverride ?: container.driveApiClient, ordnerId, vollsicherung.datei)
                    .fold(
                        onSuccess = {
                            settings.merkeVollsicherung(vollsicherung.vollsicherungId, vollsicherung.stand)
                            settings.datenbankSicherungLastSuccessAt = System.currentTimeMillis()
                            container.diagnosticsReporter.breadcrumb(
                                "DriveSync",
                                "Vollsicherung hochgeladen (${vollsicherung.datei.length()} Bytes)",
                            )
                            Result.success()
                        },
                        onFailure = { fehler ->
                            melde(container, "hochladen", fehler)
                            Result.retry()
                        },
                    )
            } finally {
                vollsicherung.datei.delete()
            }
        }

        private fun melde(
            container: com.example.lrmprotokoll.AppContainer,
            schritt: String,
            fehler: Throwable,
        ) {
            container.diagnosticsReporter.report(
                code = DiagnosticCode.BACKUP_CREATE_FAILED,
                component = "DatenbankVollsicherungWorker",
                operation = "vollsicherung.$schritt",
                severity = DiagnosticSeverity.WARN,
                cause = fehler,
                message = fehler.message,
            )
        }

        override suspend fun getForegroundInfo(): ForegroundInfo = vordergrundInfo()

        private fun vordergrundInfo(): ForegroundInfo {
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(
                NotificationChannel(
                    KANAL_ID,
                    applicationContext.getString(R.string.vollsicherung_kanal_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
            val meldung =
                NotificationCompat
                    .Builder(applicationContext, KANAL_ID)
                    .setContentTitle(applicationContext.getString(R.string.vollsicherung_benachrichtigung_titel))
                    .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                    .setOngoing(true)
                    .setProgress(0, 0, true)
                    .build()
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ForegroundInfo(NOTIFICATION_ID, meldung, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                ForegroundInfo(NOTIFICATION_ID, meldung)
            }
        }
    }

object DatenbankVollsicherungPlanung {
    private val nurWlan = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()

    /**
     * Plant die tägliche Vollsicherung um [VOLLSICHERUNG_UHRZEIT], nur im WLAN. Gibt es noch
     * keine Vollsicherung in Drive, zusätzlich eine einmalige, sobald WLAN da ist - sonst gäbe es
     * bis zur ersten Nacht auch keine Teilsicherungen. Bei jedem App-Start aufgerufen; `UPDATE`
     * hält den bestehenden Takt.
     */
    fun plane(
        context: Context,
        vollsicherungVorhanden: Boolean,
        jetzt: ZonedDateTime = ZonedDateTime.now(),
    ) {
        try {
            val workManager = WorkManager.getInstance(context)
            val nacht =
                PeriodicWorkRequestBuilder<DatenbankVollsicherungWorker>(1, TimeUnit.DAYS)
                    .setInitialDelay(verzoegerungBisVollsicherung(jetzt).toMillis(), TimeUnit.MILLISECONDS)
                    .setConstraints(nurWlan)
                    .build()
            workManager.enqueueUniquePeriodicWork(NACHT_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, nacht)
            if (!vollsicherungVorhanden) {
                val erst =
                    OneTimeWorkRequestBuilder<DatenbankVollsicherungWorker>()
                        .setConstraints(nurWlan)
                        .build()
                workManager.enqueueUniqueWork(ERST_WORK_NAME, ExistingWorkPolicy.KEEP, erst)
            }
        } catch (e: Throwable) {
            Log.w("VollsicherungPlanung", "WorkManager konnte nicht aufgerufen werden", e)
        }
    }

    /** Zeit bis zum nächsten [VOLLSICHERUNG_UHRZEIT] - heute, falls noch nicht vorbei, sonst morgen. */
    internal fun verzoegerungBisVollsicherung(jetzt: ZonedDateTime): Duration {
        var ziel = jetzt.with(VOLLSICHERUNG_UHRZEIT).withNano(0)
        if (!ziel.isAfter(jetzt)) ziel = ziel.plusDays(1)
        return Duration.between(jetzt, ziel)
    }
}
