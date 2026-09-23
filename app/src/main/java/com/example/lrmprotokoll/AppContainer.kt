package com.example.lrmprotokoll

import android.content.Context
import android.util.Log
import com.example.lrmprotokoll.alert.AlarmConfig
import com.example.lrmprotokoll.alert.AlarmCoordinator
import com.example.lrmprotokoll.alert.AlarmManagerDeadlineScheduler
import com.example.lrmprotokoll.alert.AlertChannel
import com.example.lrmprotokoll.alert.ChannelId
import com.example.lrmprotokoll.alert.heartbeat.HeartbeatPinger
import com.example.lrmprotokoll.alert.local.LocalNotificationAlertChannel
import com.example.lrmprotokoll.alert.ntfy.NtfyAlertChannel
import com.example.lrmprotokoll.data.AppDatabase
import com.example.lrmprotokoll.diagnose.DiagnosticLogCleanupCoordinator
import com.example.lrmprotokoll.diagnose.DiagnosticLogger
import com.example.lrmprotokoll.data.SettingsManager
import com.example.lrmprotokoll.drive.AccessTokenProvider
import com.example.lrmprotokoll.drive.DriveApiClient
import com.example.lrmprotokoll.drive.DriveSyncCoordinator
import com.example.lrmprotokoll.drive.GoogleDriveApiClient
import com.example.lrmprotokoll.drive.LevelSampleCollector
import com.example.lrmprotokoll.drive.auth.DriveEinrichtung
import com.example.lrmprotokoll.drive.auth.GoogleSignInAccessTokenProvider
import com.example.lrmprotokoll.messreihe.MeasurementRecorder
import com.example.lrmprotokoll.messreihe.RetentionCoordinator
import java.time.Duration
import okhttp3.OkHttpClient
import com.example.lrmprotokoll.meter.ConnectionSupervisor
import com.example.lrmprotokoll.meter.MeterTransport
import com.example.lrmprotokoll.meter.ble.BleDevice
import com.example.lrmprotokoll.meter.ble.BleMeterTransport
import com.example.lrmprotokoll.meter.ble.BluetoothAdapterStateObserver
import com.example.lrmprotokoll.meter.ble.Pce323Profile
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow

/**
 * Schlanker manueller Ersatz fuer ein DI-Framework (Plan Abschnitt 4.2): haelt die
 * Anwendung geteilten Abhaengigkeiten an einer Stelle, damit sie in Tests austauschbar sind,
 * ohne alle bestehenden Klassen mit Annotationen zu versehen.
 */
class AppContainer(
    context: Context,
    meterTransportOverride: MeterTransport? = null,
    internal val bleScanProviderOverride: (() -> Flow<BleDevice>)? = null,
    private val standortErmittlungOverride: com.example.lrmprotokoll.standort.StandortErmittlung? = null,
    private val wetterProviderOverride: com.example.lrmprotokoll.wetter.WetterProvider? = null,
) {
    val database: AppDatabase by lazy { AppDatabase.getDatabase(context) }
    val settingsManager: SettingsManager by lazy { SettingsManager(context) }
    val meterTransport: MeterTransport by lazy {
        meterTransportOverride ?: BleMeterTransport(context.applicationContext)
    }

    private val bluetoothAdapterStateObserver by lazy {
        BluetoothAdapterStateObserver(context.applicationContext)
    }

    // App-weiter Scope statt Activity-/Service-gebunden: der Verbindungsaufbau muss eine
    // geschlossene UI und Konfigurationswechsel ueberleben (PROMPT_M3 Aufgabe 3). Gestartet/
    // gestoppt wird er trotzdem vom AudioRecordingService, damit er die Absicherung eines
    // Foreground Service bekommt statt unprotokolliert im Hintergrund gedrosselt zu werden.
    //
    // CoroutineExceptionHandler als zweites Netz (Review-Befund 2, PR #16): ConnectionSupervisor
    // faengt Exceptions aus einzelnen Verbindungsversuchen bereits selbst ab, aber ohne Handler
    // hier wuerde eine Ausnahme aus einem anderen Pfad (z.B. dem forwarder-launch) den Scope
    // unbemerkt verlassen, statt wenigstens geloggt zu werden - der SupervisorJob haelt den
    // Scope zwar am Leben, aber nur der Handler verhindert, dass der Fehler spurlos verschwindet.
    private val connectionSupervisorScope by lazy {
        val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            Log.e("AppContainer", "Unerwarteter Fehler im ConnectionSupervisor-Scope", throwable)
            diagnosticsReporter.report(
                code = com.example.lrmprotokoll.diagnose.DiagnosticCode.APP_UNCAUGHT,
                component = "AppContainer",
                operation = "connectionSupervisorScope",
                severity = com.example.lrmprotokoll.diagnose.DiagnosticSeverity.ERROR,
                handled = false,
                cause = throwable,
                message = "Unerwarteter Fehler im ConnectionSupervisor-Scope"
            )
        }
        CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)
    }

    /**
     * Ein gemeinsamer Client fuer ntfy und Heartbeat: OkHttp haelt darin seinen Verbindungs- und
     * Threadpool. Zwei Instanzen waeren zwei Pools fuer insgesamt ein paar Anfragen pro Stunde.
     */
    private val httpClient: OkHttpClient by lazy { OkHttpClient() }

    val heartbeatPinger: HeartbeatPinger by lazy { HeartbeatPinger(settingsManager, httpClient) }

    /**
     * Die Kanalliste ist die einzige Stelle, an der steht, worueber alarmiert wird. Der
     * [AlarmCoordinator] kennt nur die Schnittstelle - deshalb war das Streichen des SMS-Kanals
     * eine Zeile hier und kein Eingriff in die Alarmlogik (Plan 7.6).
     */
    private val alertChannels: List<AlertChannel> by lazy {
        listOf(
            NtfyAlertChannel(settingsManager, httpClient),
            LocalNotificationAlertChannel(context.applicationContext, settingsManager),
        )
    }

    val alarmCoordinator: AlarmCoordinator by lazy {
        AlarmCoordinator(
            states = connectionSupervisor.state,
            adapterEnabled = bluetoothAdapterStateObserver.enabled,
            channels = alertChannels,
            dao = database.alertDao(),
            scheduler = AlarmManagerDeadlineScheduler(context.applicationContext),
            scope = connectionSupervisorScope,
            config = AlarmConfig(
                grace = Duration.ofSeconds(settingsManager.karenzzeitSekunden.toLong()),
                entwarnungUeber = buildSet {
                    if (settingsManager.entwarnungUeberNtfy) add(ChannelId.NTFY)
                    if (settingsManager.entwarnungUeberMeldung) add(ChannelId.LOCAL_NOTIFICATION)
                },
            ),
        )
    }

    // ---------------------------------------------------------------- M6: Sicherheit & Diagnose

    val diagnosticLogger: DiagnosticLogger by lazy {
        DiagnosticLogger(dao = database.diagnosticLogDao(), aktiv = { settingsManager.diagnoseLoggingAktiv })
    }

    val localDiagnosticSink: com.example.lrmprotokoll.diagnose.LocalDiagnosticSink by lazy {
        com.example.lrmprotokoll.diagnose.LocalDiagnosticSink(
            dao = database.diagnosticLogDao(),
            aktiv = { settingsManager.diagnoseLoggingAktiv }
        )
    }

    val sentryDiagnosticSink: com.example.lrmprotokoll.diagnose.sentry.SentryDiagnosticSink by lazy {
        com.example.lrmprotokoll.diagnose.sentry.SentryDiagnosticSink(
            aktiv = { settingsManager.remoteDiagnoseAktiv }
        )
    }

    /** M12 Schritt 2 (Konzept 4.3): behebt Luecke L3 - Breadcrumbs ueberleben jetzt den Prozesstod. */
    val breadcrumbRingFile: com.example.lrmprotokoll.diagnose.BreadcrumbRingFile by lazy {
        com.example.lrmprotokoll.diagnose.BreadcrumbRingFile(context.applicationContext.filesDir)
    }

    /** M12 Schritt 3 (Konzept 4, behebt Luecke L5): vollstaendige ExitInfo-Auswertung. */
    val processExitSource: com.example.lrmprotokoll.diagnose.ProcessExitSource by lazy {
        com.example.lrmprotokoll.diagnose.SystemProcessExitSource(context.applicationContext)
    }

    val processExitCollector: com.example.lrmprotokoll.diagnose.ProcessExitCollector by lazy {
        com.example.lrmprotokoll.diagnose.ProcessExitCollector(
            source = processExitSource,
            diagnosticsReporter = diagnosticsReporter,
            verzeichnis = java.io.File(context.applicationContext.filesDir, "process_exit_traces"),
            zuletztVerarbeitet = { settingsManager.letzterVerarbeiteterProzessExitZeitstempel },
            setzeZuletztVerarbeitet = { settingsManager.letzterVerarbeiteterProzessExitZeitstempel = it },
        )
    }

    val diagnosticsReporter: com.example.lrmprotokoll.diagnose.DiagnosticsReporter by lazy {
        com.example.lrmprotokoll.diagnose.CompositeDiagnosticsReporter(
            sinks = listOf(localDiagnosticSink, sentryDiagnosticSink),
            ringFile = breadcrumbRingFile,
            initialContext = com.example.lrmprotokoll.diagnose.DiagnosticContext(
                appVersion = "1.0",
                buildType = "debug",
            )
        )
    }

    val diagnosticLogCleanupCoordinator: DiagnosticLogCleanupCoordinator by lazy {
        DiagnosticLogCleanupCoordinator(dao = database.diagnosticLogDao())
    }

    val supportBundleExporter: com.example.lrmprotokoll.diagnose.export.SupportBundleExporter by lazy {
        com.example.lrmprotokoll.diagnose.export.SupportBundleExporter(
            context = context.applicationContext,
            reporter = diagnosticsReporter,
            diagnosticLogDao = database.diagnosticLogDao(),
            breadcrumbRingFile = breadcrumbRingFile,
            settingsManager = settingsManager,
            database = database,
            traceVerzeichnis = java.io.File(context.applicationContext.filesDir, "process_exit_traces"),
            bleVerbindungszustandProvider = { connectionSupervisor.state.value.toString() },
            aufnahmeAktivProvider = { com.example.lrmprotokoll.audio.AudioRecordingService.audioAufnahmeAktiv.value },
        )
    }

    // ---------------------------------------------------------------- O-8: ANR-Watchdog

    /** Eigener Scope fuer den Bundle-Bau nach einem Haenger - wird mit [close] beendet. */
    private val anrWatchdogScope: CoroutineScope by lazy {
        val exceptionHandler =
            CoroutineExceptionHandler { _, throwable ->
                Log.w("AppContainer", "Unerwarteter Fehler im ANR-Watchdog-Scope", throwable)
            }
        CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    }

    val anrWatchdogCoordinator: com.example.lrmprotokoll.diagnose.export.AnrWatchdogCoordinator by lazy {
        com.example.lrmprotokoll.diagnose.export.AnrWatchdogCoordinator(
            context = context.applicationContext,
            verzeichnis = java.io.File(context.applicationContext.filesDir, "process_exit_traces"),
            reporter = diagnosticsReporter,
            exporter = supportBundleExporter,
            scope = anrWatchdogScope,
        )
    }

    private val anrWatchdogLazy =
        lazy {
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            com.example.lrmprotokoll.diagnose.AnrWatchdog(
                postAufMainThread = { mainHandler.post(it) },
                mainThreadStacktrace = com.example.lrmprotokoll.diagnose.AnrWatchdog::echterMainThreadStack,
                onHaenger = anrWatchdogCoordinator::haengerErkannt,
                onErholt = anrWatchdogCoordinator::erholt,
            )
        }

    /** Gestartet von [LaermprotokollApp.onCreate], ausser unter Robolectric (siehe dort). */
    val anrWatchdog: com.example.lrmprotokoll.diagnose.AnrWatchdog by anrWatchdogLazy

    val connectionSupervisor: ConnectionSupervisor by lazy {
        ConnectionSupervisor(
            transport = meterTransport,
            scope = connectionSupervisorScope,
            adapterEnabled = bluetoothAdapterStateObserver.enabled,
            // Plan Abschnitt 6, Stream-Plausibilisierung: nur hier ist die geraetespezifische
            // Erwartung bekannt, ConnectionSupervisor selbst bleibt frei von BLE-Details.
            expectedFramePeriod = Duration.ofMillis(Pce323Profile.EXPECTED_FRAME_PERIOD_MS),
            // Owner-Entscheidung nach Geraetetest ("Toleranz lockern"): Der urspruengliche
            // ±20%-Default (ConnectionSupervisor-KDoc) loeste bei nahezu jedem Reconnect
            // faelschlich DEGRADED aus - das Diagnose-Log zeigte reale Deltas von ~180-630ms um
            // die erwarteten 515ms. ±50% deckt das ab, ohne die Kadenzpruefung ganz abzuschalten.
            cadenceTolerance = 0.5,
            diagnosticLogger = diagnosticLogger,
            diagnosticsReporter = diagnosticsReporter,
        )
    }

    // ---------------------------------------------------------------- M7b: Google-Drive-Sync

    val levelSampleCollector: LevelSampleCollector by lazy {
        LevelSampleCollector(dao = database.levelSampleDao(), scope = connectionSupervisorScope)
    }

    val driveAccessTokenProvider: GoogleSignInAccessTokenProvider by lazy {
        GoogleSignInAccessTokenProvider(context.applicationContext, settingsManager)
    }

    val driveApiClient: DriveApiClient by lazy {
        GoogleDriveApiClient(tokenProvider = driveAccessTokenProvider, client = httpClient)
    }

    val driveEinrichtung: DriveEinrichtung by lazy {
        DriveEinrichtung(context.applicationContext, settingsManager, driveApiClient, driveAccessTokenProvider)
    }

    val driveSyncCoordinator: DriveSyncCoordinator by lazy {
        DriveSyncCoordinator(
            driveApi = driveApiClient,
            levelSampleDao = database.levelSampleDao(),
            dailyFileDao = database.driveDailyFileDao(),
            noiseDao = database.noiseDao(),
            settings = settingsManager,
            dokumentationsFotoDao = database.dokumentationsFotoDao(),
            beweisVideoDao = database.beweisVideoDao(),
            diagnosticsReporter = diagnosticsReporter,
            datenbankSicherungQuelle = {
                com.example.lrmprotokoll.backup.SicherungManager.baueSicherungsBytes(context.applicationContext, settingsManager)
            },
        )
    }

    // ---------------------------------------------------------------- M11: Videobeweis

    /**
     * Nimmt den Ton fuer ein laufendes Beweisvideo mit. Liegt hier und nicht im
     * Aufnahme-Screen, weil zwei Seiten darauf zugreifen: Der Screen startet und stoppt ihn,
     * die Aufnahmeschleife des [com.example.lrmprotokoll.audio.AudioRecordingService] fuellt
     * ihn. Ein Singleton im Container ist der Ort, an dem sich beide treffen, ohne dass der
     * Service den Screen kennen muss.
     */
    val videoTonMitschnitt: com.example.lrmprotokoll.video.VideoTonMitschnitt by lazy {
        com.example.lrmprotokoll.video.VideoTonMitschnitt()
    }

    /**
     * App-weiter Scope fuer den ABSCHLUSS einer Videobeweis-Aufnahme (Praefprotokoll-Frage 8 /
     * Owner-Entscheidung vom 11.09.2026: "Korrigiere das und dokumentiere es nach").
     *
     * Vorher lief `VideoAufnahmeScreen`s `VideoRecordEvent.Finalize`-Callback auf dem
     * `rememberCoroutineScope()` des Screens - verliess der Nutzer den Screen, BEVOR CameraX das
     * Finalize-Ereignis lieferte (der Callback kommt asynchron, ohne garantierte Frist), wurde
     * die Compose-Coroutine mit dem Screen abgebrochen: `beendeAufnahme()` lief nie zu Ende,
     * `videoTonMitschnitt.beende()` wurde nie aufgerufen (die PCM-Senke schrieb unbemerkt weiter),
     * `tonGemuxt` blieb fuer immer `false`, und die tote `BeweisVideoDao.ungemuxte()`-Abfrage
     * (fuer genau diesen Fall gedacht) wurde nie aufgerufen, um es zu reparieren.
     *
     * Dasselbe Muster wie [connectionSupervisorScope]/[com.example.lrmprotokoll.drive.DriveSyncCoordinator]:
     * eine Aufgabe, die eine geschlossene UI ueberleben muss, gehoert nicht an einen
     * Compose-Scope. Anders als bei jenen kein eigener Coordinator-Typ - der Abschluss ist ein
     * einziger, bereits vorhandener Suspend-Aufruf ([com.example.lrmprotokoll.ui.beendeAufnahme]),
     * der nur den richtigen Scope braucht, keine eigene Zustandsmaschine.
     */
    val videobeweisAbschlussScope: CoroutineScope by lazy {
        val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            Log.e("AppContainer", "Unerwarteter Fehler beim Abschluss einer Videobeweis-Aufnahme", throwable)
            diagnosticsReporter.report(
                code = com.example.lrmprotokoll.diagnose.DiagnosticCode.VIDEO_MUX_FAILED,
                component = "AppContainer",
                operation = "videobeweisAbschlussScope",
                severity = com.example.lrmprotokoll.diagnose.DiagnosticSeverity.ERROR,
                handled = false,
                cause = throwable,
                message = "Unerwarteter Fehler beim Abschluss einer Videobeweis-Aufnahme",
            )
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    }

    // ---------------------------------------------------------------- M4: Messreihe

    val measurementRecorder: MeasurementRecorder by lazy {
        MeasurementRecorder(
            states = connectionSupervisor.state,
            frames = meterTransport.frames,
            sessionDao = database.sessionDao(),
            measurementDao = database.measurementDao(),
            connectionEventDao = database.connectionEventDao(),
            scope = connectionSupervisorScope,
            // Praefprotokoll-Befund 01 / Owner-Entscheidung 11.09.2026: erst dadurch bekommt
            // eine reine Mikrofon-Session ueberhaupt Ausfallbaender/eine ehrliche
            // Datenverfuegbarkeit statt strukturell immer 100%.
            mikrofonAktiv = com.example.lrmprotokoll.audio.AudioRecordingService.audioAufnahmeAktiv,
        )
    }

    val fotoDokumentation: com.example.lrmprotokoll.foto.FotoDokumentation by lazy {
        com.example.lrmprotokoll.foto.FotoDokumentation(
            context = context.applicationContext,
            dao = database.dokumentationsFotoDao(),
            settings = settingsManager,
            diagnostics = diagnosticsReporter,
        )
    }

    val wetterProvider: com.example.lrmprotokoll.wetter.WetterProvider by lazy {
        wetterProviderOverride ?: com.example.lrmprotokoll.wetter.OpenMeteoWetterProvider()
    }

    val standortErmittlung: com.example.lrmprotokoll.standort.StandortErmittlung by lazy {
        standortErmittlungOverride ?: com.example.lrmprotokoll.standort.GeraeteStandortErmittlung(context.applicationContext)
    }

    val retentionCoordinator: RetentionCoordinator by lazy {
        RetentionCoordinator(
            measurementDao = database.measurementDao(),
            minuteAggregateDao = database.minuteAggregateDao(),
        )
    }

    /**
     * CI-Fund (22.09.2026, PR #182): produktiv wird nie mehr als ein [AppContainer] pro
     * Prozesslauf gebraucht (die App ersetzt ihren Container nie - [LaermprotokollApp.setCustomContainer]/
     * [LaermprotokollApp.resetContainer] sind reine Test-Seams, siehe dort), deshalb hatte
     * [connectionSupervisorScope]/[videobeweisAbschlussScope] nie einen Abschluss noetig. In
     * Tests entsteht dagegen pro Testmethode oft ein neuer Container - ohne Abschluss laeuft der
     * alte Scope einfach weiter, angehaeuft ueber Hunderte Testmethoden im selben Gradle-Test-
     * JVM-Fork. Vermuteter Beitrag zu den sporadischen AppNotIdleException-Flakes in
     * MeterScreenComposeTest/MeterScreenPermissionAndScanTest (Issue #160, PR #179) - jener Fix
     * adressierte nur die Aktivitaet im eigenen Test, nicht die Ansammlung aus frueheren Tests.
     * Aufruf aus [LaermprotokollApp.setCustomContainer]/[LaermprotokollApp.resetContainer].
     */
    fun close() {
        connectionSupervisorScope.cancel()
        videobeweisAbschlussScope.cancel()
        if (anrWatchdogLazy.isInitialized()) anrWatchdog.stop()
        anrWatchdogScope.cancel()
    }
}
