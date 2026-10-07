package com.example.lrmprotokoll.audio

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.alert.ntfy.sendeNtfyWaechterMeldung
import com.example.lrmprotokoll.diagnose.DiagnosticCode
import com.example.lrmprotokoll.diagnose.DiagnosticSeverity
import okhttp3.OkHttpClient
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

private const val WORK_NAME = "aufzeichnungs_waechter"

/**
 * Periodischer Worker, der unerwartet beendete Aufzeichnungen erkennt und gemaess Plattformgrenzen
 * neu startet oder meldet (Plan Abschnitt 5.4, Befund G aus docs/BEFUNDE_BUNDLES_2026-10-07.md).
 *
 * ## Was dieser Waechter kann und was nicht (Abschnitt 1 aus docs/PROMPT_FIX_AUFZEICHNUNG_WAECHTER.md)
 * 1. **Prozess getoetet (Speicher, EMUI-„Bereinigung“):** JA. Der JobScheduler startet diesen
 *    Worker periodisch in einem frischen Prozess.
 * 2. **Zwangsbeendet („Beenden erzwingen“ / `forceStopPackage`):** NEIN. Android entfernt alle
 *    Jobs und Alarme vollstaendig, bis der Nutzer die App wieder von Hand oeffnet. Dagegen
 *    schuetzen die externe Totmannschaltung und der [AufzeichnungsFortsetzer] beim App-Start.
 * 3. **EMUI unterdrueckt Hintergrund-Jobs:** NEIN. Werden Jobs vom System blockiert (Befund H),
 *    kann auch dieser Worker nicht getaktet werden.
 *
 * ## Plattformgrenzen beim automatischen Dienststart
 * - **Bis Android 10 (SDK <= 29, z. B. Huawei P30):** Ein aus dem Hintergrund gestarteter
 *   Foreground-Service darf das Mikrofon verwenden. Der Waecheter startet den Dienst direkt.
 * - **Ab Android 11 (SDK >= 30):** Aus dem Hintergrund gestartete Dienste erhalten keinen
 *   Mikrofonzugriff (Stille) bzw. werfen ab Android 12 `ForegroundServiceStartNotAllowedException`.
 *   Daher wird ab SDK 30 nicht still gestartet, sondern eine Benachrichtigung zum Antippen
 *   angezeigt, die ueber die `MainActivity` den regulaeren Start ausfuehrt.
 */
class AufzeichnungsWaechterWorker @JvmOverloads constructor(
    context: Context,
    parameter: WorkerParameters,
    private val serviceStarter: ((Context, Intent) -> Unit)? = null,
    private val sdkIntOverride: Int? = null,
    private val zeitProviderOverride: (() -> Long)? = null,
    private val okHttpClientOverride: OkHttpClient? = null,
    private val notifierOverride: AufzeichnungsWaechterNotifier? = null,
    private val dienstLaeuftProvider: (() -> Boolean)? = null,
    private val kannInVordergrundProvider: (() -> Boolean)? = null,
    private val settingsManagerOverride: com.example.lrmprotokoll.data.SettingsManager? = null,
    private val levelSampleDaoOverride: com.example.lrmprotokoll.data.LevelSampleDao? = null,
    private val diagnosticsReporterOverride: com.example.lrmprotokoll.diagnose.DiagnosticsReporter? = null,
) : CoroutineWorker(context, parameter) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? LaermprotokollApp
        val container = app?.container
        val settings = settingsManagerOverride ?: container?.settingsManager ?: com.example.lrmprotokoll.data.SettingsManager(applicationContext)
        val dao = levelSampleDaoOverride ?: container?.database?.levelSampleDao()
        val reporter = diagnosticsReporterOverride ?: container?.diagnosticsReporter
            ?: com.example.lrmprotokoll.diagnose.CompositeDiagnosticsReporter(sinks = emptyList())

        val zeitProvider = zeitProviderOverride ?: { System.currentTimeMillis() }
        val sdkInt = sdkIntOverride ?: Build.VERSION.SDK_INT
        val starter = serviceStarter ?: { ctx, intent -> ContextCompat.startForegroundService(ctx, intent) }
        val notifier = notifierOverride ?: AufzeichnungsWaechterNotifier(applicationContext)
        val client = okHttpClientOverride ?: OkHttpClient()

        val laeuft = dienstLaeuftProvider?.invoke() ?: AudioRecordingService.laeuft.value
        val kannInVordergrund =
            kannInVordergrundProvider?.invoke() ?: kannDienstInDenVordergrund(applicationContext, settings)

        val lage =
            bewerteAufzeichnungsLage(
                monitoringWasActive = settings.monitoringWasActive,
                dienstLaeuft = laeuft,
                kannInDenVordergrund = kannInVordergrund,
            )

        when (lage) {
            AufzeichnungsLage.LAEUFT,
            AufzeichnungsLage.AUS,
            -> {
                // Nichts tun, nichts protokollieren (kein Spam alle 15 Minuten)
                return Result.success()
            }

            AufzeichnungsLage.SOLL_ABER_NICHT_STARTBAR -> {
                reporter.report(
                    code = DiagnosticCode.PERMISSION_REVOKED_DURING_OPERATION,
                    component = "AufzeichnungsWaechter",
                    operation = "doWork",
                    severity = DiagnosticSeverity.WARN,
                    message = "Aufzeichnung soll laufen, kann aber nicht gestartet werden: weder Mikrofonberechtigung noch gekoppeltes Messgeraet",
                    details = mapOf("quelle" to "waechter"),
                )
                return Result.success()
            }

            AufzeichnungsLage.SOLL_LAEUFT_NICHT -> {
                val jetzt = zeitProvider()
                val letzteDatenAt = runCatching { dao?.maxAt() }.getOrNull()
                val lueckeMinuten =
                    if (letzteDatenAt != null && letzteDatenAt < jetzt) {
                        (jetzt - letzteDatenAt) / (60 * 1000L)
                    } else {
                        0L
                    }

                reporter.report(
                    code = DiagnosticCode.RECORDING_ENDED_UNEXPECTEDLY,
                    component = "AufzeichnungsWaechter",
                    operation = "doWork",
                    severity = DiagnosticSeverity.WARN,
                    message = "Aufzeichnung unerwartet beendet (Waechter)",
                    details =
                        mapOf(
                            "letzteDatenAt" to letzteDatenAt,
                            "entdecktAt" to jetzt,
                            "lueckeMinuten" to lueckeMinuten,
                            "audioWarAktiv" to settings.audioMonitoringWasActive,
                            "quelle" to "waechter",
                        ),
                )

                settings.speichereUnterbrechung(beginn = letzteDatenAt, ende = jetzt)

                val zeitText =
                    if (letzteDatenAt != null) {
                        runCatching {
                            Instant.ofEpochMilli(letzteDatenAt)
                                .atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ofPattern("HH:mm"))
                        }.getOrDefault("unbekannt")
                    } else {
                        "unbekannt"
                    }

                val darfStarten = settings.kannWaechterNeuStarten(jetzt)
                if (!darfStarten) {
                    // Schleifenschutz greift (E2: max. 3 Neustarts pro Stunde)
                    notifier.benachrichtigeZumFortsetzen(
                        titel = "Lärmprotokoll",
                        nachricht = "Neustart der Aufzeichnung wiederholt gescheitert – bitte am Gerät prüfen",
                    )
                    sendeNtfyWaechterMeldung(
                        settings = settings,
                        nachricht = "Neustart der Aufzeichnung wiederholt gescheitert – bitte am Gerät prüfen.",
                        client = client,
                    ).onFailure { e ->
                        reporter.report(
                            code = DiagnosticCode.ALERT_NTFY_FAILED,
                            component = "AufzeichnungsWaechter",
                            operation = "ntfySchleifenschutz",
                            severity = DiagnosticSeverity.WARN,
                            cause = e,
                            message = "ntfy-Fernmeldung bei Schleifenschutz fehlgeschlagen",
                        )
                    }
                    reporter.report(
                        code = DiagnosticCode.RECORDING_ENDED_UNEXPECTEDLY,
                        component = "AufzeichnungsWaechter",
                        operation = "schleifenschutz",
                        severity = DiagnosticSeverity.WARN,
                        message = "Schleifenschutz aktiv: Maximale Neustarts pro Stunde erreicht",
                        details = mapOf("count" to settings.waechterNeustartCount),
                    )
                    return Result.success()
                }

                if (sdkInt <= 29) {
                    settings.registriereWaechterNeustart(jetzt)
                    try {
                        val serviceIntent =
                            Intent(applicationContext, AudioRecordingService::class.java).apply {
                                putExtra(EXTRA_START_AUDIO_MONITORING, settings.audioMonitoringWasActive)
                            }
                        starter(applicationContext, serviceIntent)
                        reporter.breadcrumb("AufzeichnungsWaechter", "Aufzeichnungsdienst erfolgreich neu gestartet")
                    } catch (e: Throwable) {
                        reporter.report(
                            code = DiagnosticCode.AUDIO_FOREGROUND_SERVICE_FAILED,
                            component = "AufzeichnungsWaechter",
                            operation = "serviceStarter",
                            severity = DiagnosticSeverity.ERROR,
                            cause = e,
                            message = "Dienst konnte nicht neu gestartet werden: ${e.message}",
                        )
                    }

                    sendeNtfyWaechterMeldung(
                        settings = settings,
                        nachricht = "Aufzeichnung war vom System beendet (letzte Daten $zeitText) und wurde neu gestartet.",
                        client = client,
                    ).onFailure { e ->
                        reporter.report(
                            code = DiagnosticCode.ALERT_NTFY_FAILED,
                            component = "AufzeichnungsWaechter",
                            operation = "ntfyNeustart",
                            severity = DiagnosticSeverity.WARN,
                            cause = e,
                            message = "ntfy-Fernmeldung nach Neustart fehlgeschlagen",
                        )
                    }
                } else {
                    val benachrichtigt = notifier.benachrichtigeZumFortsetzen()
                    if (!benachrichtigt) {
                        reporter.breadcrumb("AufzeichnungsWaechter", "Benachrichtigung mangels POST_NOTIFICATIONS uebersprungen")
                    }

                    sendeNtfyWaechterMeldung(
                        settings = settings,
                        nachricht = "Aufzeichnung wurde vom System beendet (letzte Daten $zeitText). Zum Fortsetzen die App öffnen.",
                        client = client,
                    ).onFailure { e ->
                        reporter.report(
                            code = DiagnosticCode.ALERT_NTFY_FAILED,
                            component = "AufzeichnungsWaechter",
                            operation = "ntfyHinweis",
                            severity = DiagnosticSeverity.WARN,
                            cause = e,
                            message = "ntfy-Fernmeldung bei Benachrichtigung fehlgeschlagen",
                        )
                    }
                }

                return Result.success()
            }
        }
    }
}

object AufzeichnungsWaechterPlanung {

    fun plane(context: Context) {
        try {
            val anfrage =
                PeriodicWorkRequestBuilder<AufzeichnungsWaechterWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                anfrage,
            )
        } catch (e: Throwable) {
            android.util.Log.w("AufzeichnungsWaechterPlanung", "WorkManager konnte nicht aufgerufen werden", e)
        }
    }

    fun stoppe(context: Context) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        } catch (e: Throwable) {
            android.util.Log.w("AufzeichnungsWaechterPlanung", "WorkManager konnte nicht aufgerufen werden", e)
        }
    }
}
