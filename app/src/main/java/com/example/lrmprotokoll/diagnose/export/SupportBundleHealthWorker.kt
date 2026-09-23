package com.example.lrmprotokoll.diagnose.export

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.data.ConnectionEventDao
import com.example.lrmprotokoll.data.DiagnosticLogDao
import com.example.lrmprotokoll.data.SettingsManager
import com.example.lrmprotokoll.diagnose.DiagnosticsReporter
import com.example.lrmprotokoll.diagnose.acra.SUPPORT_OUTBOX_DIR
import com.example.lrmprotokoll.meter.InstantSource
import java.io.File
import java.util.concurrent.TimeUnit

private const val TAG = "SupportBundleHealth"

/**
 * M12 Schritt 6 (Konzept Abschnitt 6 Schritt 6): Entscheidungslogik fuer das periodische
 * Gesundheits-Bundle - reine WorkManager-Glue liegt in [SupportBundleHealthWorker], wie bei
 * [com.example.lrmprotokoll.diagnose.DiagnosticLogCleanupCoordinator]/-Worker.
 *
 * Laeuft im Hauptprozess (nicht im ACRA-Sender-Prozess): braucht Room, [DiagnosticsReporter] und
 * den Exporter, die im ACRA-Prozess bewusst nicht aufgebaut werden (Konzept 4.1/4.2).
 */
class SupportBundleHealthCoordinator(
    private val context: Context,
    private val connectionEventDao: ConnectionEventDao,
    private val diagnosticLogDao: DiagnosticLogDao,
    private val settingsManager: SettingsManager,
    private val reporter: DiagnosticsReporter,
    private val exporter: SupportBundleExporter,
    private val now: InstantSource = InstantSource.System,
    /** Testseam: liefert im Test einen festen Wert statt der echten Heap-Nutzung. */
    private val heapVerwendetBytesProvider: () -> Long = {
        Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
    },
    /** Testseam: der Datenbankdateiname ist in [SupportBundleExporter.buildDbStatsJson] bewusst
     * dupliziert statt einer neuen Konstante - hier aus demselben Grund noch einmal. */
    private val dbDateiProvider: () -> File = { context.getDatabasePath("noise_database") },
) {

    /** @return `true`, wenn ein Bundle erstellt (und zum Upload eingereiht) wurde. */
    suspend fun pruefeUndErstelleBundleFallsNoetig(): Boolean {
        if (!settingsManager.periodischesGesundheitsBundleAktiv) return false

        val jetzt = now.now().toEpochMilli()
        val letzterLauf = settingsManager.supportBundleGesundheitLetzterLaufAt
        val dbDatei = dbDateiProvider()
        val aktuelleDbGroesse = if (dbDatei.exists()) dbDatei.length() else 0L

        val metrics = berechneHealthMetrics(
            connectionEventsSeitLetztemBundle = connectionEventDao.seit(letzterLauf),
            diagnosticLogEntryCountSeitLetztemBundle = diagnosticLogDao.anzahlSeit(letzterLauf),
            dbGroesseAktuellBytes = aktuelleDbGroesse,
            dbGroesseLetztesBundleBytes = settingsManager.supportBundleGesundheitLetzteDbGroesseBytes,
            heapHochstandBytes = heapVerwendetBytesProvider(),
            // Review-Fund (Copilot, PR #182): reporter.recentEvents() liefert die im Reporter
            // gehaltene RAM-Historie (CompositeDiagnosticsReporter, auf 100 Eintraege begrenzt),
            // NICHT die Events seit letzterLauf - ohne diesen Filter wuerde ein alter Fehler, der
            // noch in der Historie steht, bei jedem taeglichen Lauf erneut gezaehlt und
            // "Kein Bundle ohne Not" nie mehr zuschlagen. Die 100er-Obergrenze selbst bleibt eine
            // bewusste Naeherung (siehe HealthMetrics-KDoc) - Events, die aelter als die letzten
            // 100 in der RAM-Historie sind, aber neuer als letzterLauf, gehen so verloren; eine
            // vollstaendige, zeitlich unbegrenzte Erfassung braeuchte eine eigene Room-Tabelle wie
            // DiagnosticLogDao und waere eine groessere, hier nicht beauftragte Aenderung.
            eventsSeitLetztemBundle = reporter.recentEvents(Int.MAX_VALUE)
                .filter { it.timestampUtc.toEpochMilli() >= letzterLauf },
        )

        // Zeitstempel/Basiswert IMMER fortschreiben, auch wenn kein Bundle entsteht - sonst
        // wuerde ein "unveraendert"-Lauf beim naechsten Mal denselben (dann laengeren) Zeitraum
        // noch einmal einbeziehen und Reconnects/Eintraege aus einer bereits gemeldeten Periode
        // doppelt zaehlen.
        settingsManager.supportBundleGesundheitLetzterLaufAt = jetzt
        settingsManager.supportBundleGesundheitLetzteDbGroesseBytes = aktuelleDbGroesse

        if (metrics.istUnveraendert()) {
            return false
        }

        val bundleDatei = exporter.createBundle(
            BundleKontext(
                typ = BundleTyp.PERIODISCH,
                ausloeser = "periodisch",
                healthMetricsJson = metrics.toJson().toString(),
            )
        )
        val outboxDir = File(context.filesDir, SUPPORT_OUTBOX_DIR).apply { mkdirs() }
        val ziel = File(outboxDir, bundleDatei.name)
        bundleDatei.copyTo(ziel, overwrite = true)
        bundleDatei.delete()

        SupportBundleUploadPlanung.planeSofort(context)
        // Kein Fallback ohne Netzbeschraenkung (anders als beim Absturz-Pfad in Schritt 5):
        // periodische Bundles sind nichts, wofuer Mobilfunkvolumen draufgehen soll (Konzept 4.7).

        return true
    }
}

/**
 * M12 Schritt 6: taeglicher Gesundheits-Job (Konzept Abschnitt 6 Schritt 6). Reine WorkManager-
 * Glue - die Entscheidungslogik steht in [SupportBundleHealthCoordinator].
 */
class SupportBundleHealthWorker @JvmOverloads constructor(
    context: Context,
    parameter: WorkerParameters,
    /** Testseam wie bei [SupportBundleUploadWorker]: `null` verwendet den echten Koordinator aus
     * dem Container. */
    private val coordinatorOverride: SupportBundleHealthCoordinator? = null,
) : CoroutineWorker(context, parameter) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as LaermprotokollApp).container
        val coordinator = coordinatorOverride ?: SupportBundleHealthCoordinator(
            context = applicationContext,
            connectionEventDao = container.database.connectionEventDao(),
            diagnosticLogDao = container.database.diagnosticLogDao(),
            settingsManager = container.settingsManager,
            reporter = container.diagnosticsReporter,
            exporter = container.supportBundleExporter,
        )
        return runCatching { coordinator.pruefeUndErstelleBundleFallsNoetig() }
            .fold(
                onSuccess = { Result.success() },
                onFailure = {
                    Log.w(TAG, "Gesundheits-Bundle konnte nicht erstellt werden", it)
                    Result.retry()
                },
            )
    }
}

/**
 * WorkManager-Glue fuer [SupportBundleHealthWorker] (Konzept Schritt 6 Aufgabe 1: alle 24h,
 * `UNMETERED`, kein Fallback auf Mobilfunk).
 */
object SupportBundleHealthPlanung {

    private const val WORK_NAME = "support_bundle_health"

    /**
     * Immer geplant (anders als [com.example.lrmprotokoll.diagnose.DiagnosticLogCleanupPlanung],
     * die nur bei aktivem Diagnose-Log laeuft) - der Abschalter ist
     * [SettingsManager.periodischesGesundheitsBundleAktiv], ausgewertet in
     * [SupportBundleHealthCoordinator] bei jedem Lauf, nicht die Planung selbst. So verhindert
     * das Umschalten in Schritt 8 sowohl Erzeugung als auch Upload, ohne dass die UI die
     * WorkManager-Planung kennen muss.
     */
    fun plane(context: Context) {
        try {
            val anfrage = PeriodicWorkRequestBuilder<SupportBundleHealthWorker>(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                anfrage,
            )
        } catch (e: Throwable) {
            Log.w(TAG, "WorkManager konnte nicht aufgerufen werden", e)
        }
    }
}
