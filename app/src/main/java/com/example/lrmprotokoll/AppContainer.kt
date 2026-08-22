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
import com.example.lrmprotokoll.meter.ble.BleMeterTransport
import com.example.lrmprotokoll.meter.ble.BluetoothAdapterStateObserver
import com.example.lrmprotokoll.meter.ble.Pce323Profile
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Schlanker manueller Ersatz fuer ein DI-Framework (Plan Abschnitt 4.2): haelt die
 * Anwendung geteilten Abhaengigkeiten an einer Stelle, damit sie in Tests austauschbar sind,
 * ohne alle bestehenden Klassen mit Annotationen zu versehen.
 */
class AppContainer(context: Context) {
    val database: AppDatabase by lazy { AppDatabase.getDatabase(context) }
    val settingsManager: SettingsManager by lazy { SettingsManager(context) }
    val meterTransport: MeterTransport by lazy { BleMeterTransport(context.applicationContext) }

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
            LocalNotificationAlertChannel(context.applicationContext),
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

    val diagnosticsReporter: com.example.lrmprotokoll.diagnose.DiagnosticsReporter by lazy {
        com.example.lrmprotokoll.diagnose.CompositeDiagnosticsReporter(
            sinks = listOf(localDiagnosticSink, sentryDiagnosticSink),
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
            reporter = diagnosticsReporter
        )
    }

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
        )
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
        )
    }

    val retentionCoordinator: RetentionCoordinator by lazy {
        RetentionCoordinator(
            measurementDao = database.measurementDao(),
            minuteAggregateDao = database.minuteAggregateDao(),
        )
    }
}
