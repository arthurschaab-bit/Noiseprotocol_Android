package com.example.lrmprotokoll.drive

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.lrmprotokoll.LaermprotokollApp
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder

private const val WORK_NAME = "drive_sync"
private const val RETRY_TYP = "retry_typ"
private const val RETRY_KENNUNG = "retry_kennung"

/**
 * Der periodische Sync-Auftrag (Plan Abschnitt 8.4.5). Reine WorkManager-Glue - die
 * Entscheidungslogik steht in [DriveSyncCoordinator] und ist ohne diese Klasse testbar.
 *
 * `PeriodicWorkRequest` ist bewusst NICHT exakt getaktet, anders als die Karenzzeit in M5:
 * Bei Netzverlust waere ein exakter Wecker ohnehin nutzlos, und die Datenlage auf dem Geraet
 * bleibt vollstaendig (Plan 8.4.5) - Puenktlichkeit ist hier keine Anforderung.
 */
class DriveSyncWorker @JvmOverloads constructor(
    context: Context,
    parameter: WorkerParameters,
    /** Testluecken-Auftrag Stufe 2: Testseam - `null` (Standard) verwendet den echten
     * Koordinator aus dem Container, wie bisher. Ein Test uebergibt hier einen mit
     * Fake-Abhaengigkeiten aufgebauten [DriveSyncCoordinator] ueber eine eigene [WorkerFactory],
     * ohne den echten (netzwerkbehafteten) Container-Pfad ueberhaupt zu beruehren. */
    private val coordinatorOverride: DriveSyncCoordinator? = null,
) : CoroutineWorker(context, parameter) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as LaermprotokollApp).container
        val coordinator = coordinatorOverride ?: container.driveSyncCoordinator
        val startZeit = System.currentTimeMillis()
        container.diagnosticsReporter.breadcrumb("DriveSync", "Drive-Sync-Zyklus gestartet")
        val retryTyp = inputData.getString(RETRY_TYP)
        try {
            val ergebnis = if (retryTyp == null) {
                coordinator.syncEinenZyklus()
            } else {
                val typ = runCatching { UploadDateiTyp.valueOf(retryTyp) }.getOrNull() ?: return Result.failure()
                val kennung = inputData.getString(RETRY_KENNUNG) ?: return Result.failure()
                coordinator.wiederholeDatei(UploadDateiZiel(typ, kennung))
            }
            return when (ergebnis) {
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
                    if (ergebnis.wiederholbar) Result.retry() else Result.failure()
                }
            }
        } catch (e: java.util.concurrent.CancellationException) {
            val laufzeitMs = System.currentTimeMillis() - startZeit
            val reason = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                runCatching { stopReason }.getOrDefault(-1)
            } else {
                -1
            }
            container.diagnosticsReporter.breadcrumb(
                "DriveSync",
                "Drive-Sync-Worker abgebrochen nach ${laufzeitMs}ms (stopReason=$reason)"
            )
            throw e
        }
    }
}

object DriveSyncPlanung {

    // Atomar statt einfachem var: starteSofort() kommt aus Dienst, Worker und UI zugleich, und
    // ein Pruefen-dann-Setzen liesse zwei gleichzeitige Aufrufe beide durch die Drossel.
    private val letzterSofortstartBreadcrumbEpochMs = AtomicLong(0L)

    @androidx.annotation.VisibleForTesting
    internal fun resetDrosselung() {
        letzterSofortstartBreadcrumbEpochMs.set(0L)
    }

    /** Wartet auf das erlaubte Netz und synchronisiert nur die ausgewaehlte Datei. */
    fun starteDateiErneut(context: Context, ziel: UploadDateiZiel): Boolean {
        val app = context.applicationContext as? LaermprotokollApp ?: return false
        val settings = app.container.settingsManager
        if (!settings.driveSyncEnabled || settings.driveFolderId == null || settings.driveOrdnerBlockiert) return false
        val netz = if (settings.driveWlanOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val anfrage = OneTimeWorkRequestBuilder<DriveSyncWorker>()
            .setInputData(workDataOf(RETRY_TYP to ziel.typ.name, RETRY_KENNUNG to ziel.kennung))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(netz).build())
            .build()
        return runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "${WORK_NAME}_retry_${ziel.typ.name}_${ziel.kennung}",
                ExistingWorkPolicy.KEEP,
                anfrage,
            )
            true
        }.getOrDefault(false)
    }

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
     *
     * `KEEP` statt `REPLACE` (OOM-Bugfix Schritt 2, PROMPT_FIX_OOM_DRIVE_SYNC.md / Befund A1,
     * Owner-Entscheidung 23.09.2026): `REPLACE` brach einen noch laufenden bzw. eingeplanten
     * Sofortlauf ab, sobald waehrend seiner Laufzeit ein weiteres Laermereignis eintraf - genau
     * das liess das 30-Tage-Nachholen (`DriveSyncCoordinator.holeVersaeumteTageNach`) nie fertig
     * werden ("Job was cancelled" im Logcat, `drive_daily_files` blieb bei 9 Eintraegen haengen).
     *
     * Owner-Vorgabe: Trifft ein Ereignis ein, waehrend ein Sofortlauf bereits laeuft, bekommt es
     * KEINEN eigenen Lauf mehr - es geht spaetestens mit dem naechsten periodischen Lauf (<= 30
     * min, [plane]) nach Drive. Vier Aufrufer loesen `starteSofort()` aus ([AudioRecordingService]
     * nach jeder WAV-Aufnahme, `FotoDokumentationSheet` und `ProtokollDetailScreen` fuer
     * Beweismaterial, [com.example.lrmprotokoll.video.VideoMuxWorker] nach dem Muxen) - alle
     * kommentieren ihren Aufruf mit "sofort hochladen, nicht auf den naechsten Zyklus warten".
     * Keiner verlangt zwingend einen GARANTIERTEN sofortigen Upload (kein Rueckgabewert wird
     * ausgewertet, keine UI wartet auf den Abschluss) - die 30-Minuten-Verzoegerung im
     * Kollisionsfall ist ein bewusst in Kauf genommener Zielkonflikt, kein unbemerkter.
     */
    fun starteSofort(context: Context) {
        val app = context.applicationContext as? LaermprotokollApp
        val jetztMs = System.currentTimeMillis()
        val letzter = letzterSofortstartBreadcrumbEpochMs.get()
        val faellig = letzter == 0L || jetztMs - letzter >= 10 * 60 * 1000L || jetztMs < letzter
        if (faellig && letzterSofortstartBreadcrumbEpochMs.compareAndSet(letzter, jetztMs)) {
            app?.container?.diagnosticsReporter?.breadcrumb("DriveSync", "Drive-Sync sofort angefordert")
        }
        val wlanOnly = app?.container?.settingsManager?.driveWlanOnly ?: false
        val einschraenkungen = Constraints.Builder()
            .setRequiredNetworkType(if (wlanOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()

        try {
            val anfrage = OneTimeWorkRequestBuilder<DriveSyncWorker>()
                .setConstraints(einschraenkungen)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "${WORK_NAME}_immediate",
                ExistingWorkPolicy.KEEP,
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
