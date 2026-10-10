package com.example.lrmprotokoll.audio

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
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.drive.DriveAblage
import com.example.lrmprotokoll.drive.DriveSyncPlanung
import com.example.lrmprotokoll.messreihe.unklassifizierteAufnahmen
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

private const val TAG = "KiBatchWorker"
private const val KANAL_ID = "ki_batch_channel"
private const val NOTIFICATION_ID = 4720

/** Name des einmaligen Laufs (Menü, Tagesknopf). Die UI beobachtet ihn für den Fortschritt. */
const val KI_BATCH_WORK_NAME = "ki_batch"
const val KI_NACHTLAUF_WORK_NAME = "ki_batch_nacht"

/**
 * Markiert manuell gestartete Läufe. Die Startseite zeigt einen manuellen Lauf schon ab
 * `ENQUEUED`, den Nachtlauf nur während `RUNNING` - ein periodischer Auftrag steht zwischen
 * seinen Läufen dauerhaft auf `ENQUEUED`.
 */
const val KI_BATCH_TAG_MANUELL = "ki_batch_manuell"

/** Startzeit des Nachtlaufs - vor der Vollsicherung um 03:00, damit deren Stand die Labels enthält. */
private val NACHTLAUF_UHRZEIT: LocalTime = LocalTime.of(1, 30)

/**
 * Der KI-Batch als Hintergrundarbeit (Owner-Entscheidung 10.10.2026,
 * docs/PROMPT_KI_BATCH_HINTERGRUND.md). Vorher lief die Schleife im Coroutine-Scope des
 * Bildschirms: Bildschirm aus oder App geschlossen hiess Batch angehalten oder abgebrochen, und
 * die Inferenz lief auf dem Main-Thread.
 *
 * Läuft als Vordergrundarbeit mit Fortschritts-Benachrichtigung und Abbrechen-Knopf. Die
 * Kandidaten bestimmt der Worker selbst aus der Datenbank (nicht als Eingabe übergeben - 35.000
 * IDs passen nicht in die 10-KB-Grenze von WorkManager-Daten). Die Tage der neu klassifizierten
 * Aufnahmen werden für den Drive-Nachtrag vorgemerkt.
 */
class KiBatchWorker
    @JvmOverloads
    constructor(
        context: Context,
        parameter: WorkerParameters,
        /** Testseam wie in [com.example.lrmprotokoll.messreihe.RetentionWorker]. */
        private val classifierOverride: RohdatenClassifier? = null,
    ) : CoroutineWorker(context, parameter) {
        override suspend fun doWork(): Result {
            val container = (applicationContext as LaermprotokollApp).container
            val settings = container.settingsManager
            val nachtlauf = inputData.getBoolean(KEY_NACHTLAUF, false)
            if (nachtlauf && (!settings.kiNachtlauf || settings.aiMode == "OFF")) return Result.success()

            // Review zu #273: Nacht- und Handlauf laufen unter verschiedenen Work-Namen. Ohne
            // gemeinsame Sperre koennten beide dieselben Kandidaten waehlen und fuer eine
            // Aufnahme zwei Rohdatensaetze anlegen. Die Sperre umfasst schon die Kandidatenwahl:
            // wer wartet, sieht danach nur noch, was der erste Lauf uebrig gelassen hat.
            return LAUF_SPERRE.withLock { klassifiziere(container, settings) }
        }

        private suspend fun klassifiziere(
            container: com.example.lrmprotokoll.AppContainer,
            settings: com.example.lrmprotokoll.data.SettingsManager,
        ): Result {
            val dao = container.database.noiseDao()
            val von = inputData.getLong(KEY_VON, Long.MIN_VALUE)
            val bis = inputData.getLong(KEY_BIS, Long.MAX_VALUE)
            val kandidaten = unklassifizierteAufnahmen(dao.getAlleAktiven()).filter { it.timestamp in von until bis }
            if (kandidaten.isEmpty()) return Result.success(workDataOf(KEY_ANZAHL to 0))

            // Review zu #273 (P1): Die Tage werden VOR dem ersten gespeicherten Label dauerhaft
            // vorgemerkt. Stirbt der Prozess mitten im Batch, laeuft kein finally - und die schon
            // klassifizierten Aufnahmen sind fuer spaetere Laeufe keine Kandidaten mehr, ihr
            // Nachtrag waere sonst fuer immer verloren. Ein vorgemerkter Tag ohne neue Labels
            // kostet nur einen ueberfluessigen, kleinen Upload.
            val zone = ZoneId.systemDefault()
            settings.merkeKiNachtragVor(kandidaten.map { DriveAblage.tagesordner(it.timestamp, zone) }.toSet())

            // Kein Abbruch, wenn das System den Vordergrunddienst verweigert (ab Android 12 aus dem
            // Hintergrund moeglich): der Lauf geht dann als gewoehnliche Hintergrundarbeit weiter.
            runCatching { setForeground(vordergrundInfo(0, kandidaten.size)) }
                .onFailure { Log.w(TAG, "Vordergrund nicht moeglich, laufe im Hintergrund weiter", it) }

            var letzteMeldung = 0L
            var etwasGespeichert = false
            val anzahl =
                klassifiziereUndSpeichere(
                    kandidaten = kandidaten,
                    classifier = classifierOverride ?: NoiseClassifier(applicationContext),
                    dao = dao,
                    rohdatenDao = container.database.klassifikationsRohdatenDao(),
                    onGespeichert = { record ->
                        etwasGespeichert = true
                        // Hat ein Drive-Upload den Tag inzwischen uebernommen, gehoert dieses
                        // Label noch nicht dazu: neu vormerken (neue Generation, Review P2).
                        val tag = DriveAblage.tagesordner(record.timestamp, zone)
                        if (settings.brauchtKiNachtragsVormerkung(tag)) settings.merkeKiNachtragVor(listOf(tag))
                    },
                    onFortschritt = { fertig, gesamt ->
                        setProgressAsync(workDataOf(KEY_FERTIG to fertig, KEY_GESAMT to gesamt))
                        val jetzt = System.currentTimeMillis()
                        if (jetzt - letzteMeldung >= 2_000L || fertig == gesamt) {
                            letzteMeldung = jetzt
                            runCatching { setForegroundAsync(vordergrundInfo(fertig, gesamt)) }
                        }
                    },
                )
            if (etwasGespeichert) DriveSyncPlanung.starteSofort(applicationContext)
            return Result.success(workDataOf(KEY_ANZAHL to anzahl))
        }

        override suspend fun getForegroundInfo(): ForegroundInfo = vordergrundInfo(0, 0)

        private fun vordergrundInfo(
            fertig: Int,
            gesamt: Int,
        ): ForegroundInfo {
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(
                NotificationChannel(
                    KANAL_ID,
                    applicationContext.getString(R.string.ki_batch_kanal_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
            val abbrechen = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
            val meldung =
                NotificationCompat
                    .Builder(applicationContext, KANAL_ID)
                    .setContentTitle(applicationContext.getString(R.string.ki_batch_benachrichtigung_titel))
                    .setContentText(
                        if (gesamt > 0) {
                            applicationContext.getString(R.string.ai_batch_progress, fertig, gesamt)
                        } else {
                            applicationContext.getString(R.string.ai_batch_running)
                        },
                    ).setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setProgress(gesamt, fertig, gesamt == 0)
                    .addAction(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        applicationContext.getString(R.string.ki_batch_abbrechen),
                        abbrechen,
                    ).build()
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ForegroundInfo(NOTIFICATION_ID, meldung, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                ForegroundInfo(NOTIFICATION_ID, meldung)
            }
        }

        companion object {
            /** Prozessweit: serialisiert Nacht- und Handlauf (beide laufen im App-Prozess). */
            private val LAUF_SPERRE = Mutex()

            const val KEY_VON = "von"
            const val KEY_BIS = "bis"
            const val KEY_NACHTLAUF = "nachtlauf"
            const val KEY_FERTIG = "fertig"
            const val KEY_GESAMT = "gesamt"
            const val KEY_ANZAHL = "anzahl"
        }
    }

object KiBatchPlanung {
    /**
     * Startet einen Lauf über alle unklassifizierten Aufnahmen oder nur die in [von, bis).
     * `KEEP`: Läuft schon ein Batch, wird kein zweiter eingeplant - der laufende erfasst ohnehin
     * alle Kandidaten bzw. der Nutzer startet den Tag danach erneut. Liefert die ID der neuen
     * Anfrage; hat `KEEP` sie verworfen, taucht diese ID nie in den WorkInfos auf.
     */
    fun starteJetzt(
        context: Context,
        von: Long? = null,
        bis: Long? = null,
    ): java.util.UUID {
        val daten =
            workDataOf(
                KiBatchWorker.KEY_VON to (von ?: Long.MIN_VALUE),
                KiBatchWorker.KEY_BIS to (bis ?: Long.MAX_VALUE),
            )
        val anfrage =
            OneTimeWorkRequestBuilder<KiBatchWorker>()
                .setInputData(daten)
                .addTag(KI_BATCH_TAG_MANUELL)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
        WorkManager.getInstance(context).enqueueUniqueWork(KI_BATCH_WORK_NAME, ExistingWorkPolicy.KEEP, anfrage)
        return anfrage.id
    }

    /**
     * Plant oder entfernt den Nachtlauf je nach [com.example.lrmprotokoll.data.SettingsManager.kiNachtlauf].
     * Bei jedem App-Start und beim Umschalten aufgerufen. `UPDATE` hält den bestehenden Takt,
     * statt bei jedem App-Start eine neue Startverzögerung zu setzen.
     */
    fun planeNachtlauf(
        context: Context,
        aktiv: Boolean,
        jetzt: ZonedDateTime = ZonedDateTime.now(),
    ) {
        try {
            val workManager = WorkManager.getInstance(context)
            if (!aktiv) {
                workManager.cancelUniqueWork(KI_NACHTLAUF_WORK_NAME)
                return
            }
            val anfrage =
                PeriodicWorkRequestBuilder<KiBatchWorker>(1, TimeUnit.DAYS)
                    .setInitialDelay(verzoegerungBisNachtlauf(jetzt).toMillis(), TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf(KiBatchWorker.KEY_NACHTLAUF to true))
                    .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                    .build()
            workManager.enqueueUniquePeriodicWork(KI_NACHTLAUF_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, anfrage)
        } catch (e: Throwable) {
            Log.w("KiBatchPlanung", "WorkManager konnte nicht aufgerufen werden", e)
        }
    }

    /** Zeit bis zum nächsten [NACHTLAUF_UHRZEIT] - heute, falls noch nicht vorbei, sonst morgen. */
    internal fun verzoegerungBisNachtlauf(jetzt: ZonedDateTime): Duration {
        var ziel = jetzt.with(NACHTLAUF_UHRZEIT).withSecond(0).withNano(0)
        if (!ziel.isAfter(jetzt)) ziel = ziel.plusDays(1)
        return Duration.between(jetzt, ziel)
    }
}
