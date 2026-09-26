package com.example.lrmprotokoll.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.lrmprotokoll.BuildConfig
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.Versionskennung
import com.example.lrmprotokoll.audio.AudioRecordingService
import com.example.lrmprotokoll.data.DiagnosticLogEntity
import com.example.lrmprotokoll.data.DriveDailyFileEntity
import com.example.lrmprotokoll.data.DriveSyncState
import com.example.lrmprotokoll.diagnose.DiagnosticCode
import com.example.lrmprotokoll.diagnose.DiagnosticSeverity
import com.example.lrmprotokoll.diagnose.HealthActionType
import com.example.lrmprotokoll.diagnose.HealthStatus
import com.example.lrmprotokoll.diagnose.SystemHealthParams
import com.example.lrmprotokoll.diagnose.bewerteSystemZustand
import com.example.lrmprotokoll.drive.DriveSyncCoordinator
import com.example.lrmprotokoll.drive.DriveSyncPlanung
import com.example.lrmprotokoll.messreihe.zaehleReconnects
import com.example.lrmprotokoll.meter.ble.BluetoothPermissions
import com.example.lrmprotokoll.meter.label
import com.example.lrmprotokoll.ui.theme.statusColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Der Diagnose-Screen (Plan Abschnitt 9) - "kein Luxus": bei einer Dauerüberwachung, die
 * alarmiert, muss nachvollziehbar sein, warum ein Alarm ausgelöst wurde oder ausblieb.
 *
 * Enthält Live-Status, Remote-Diagnose, Diagnose-Log, F3 System-Selbstprüfung, F15 Alarm-Historie
 * und Google Drive Sync-Historie.
 */
const val DIAGNOSE_LAZY_COLUMN_TAG = "diagnose_lazy_column"
const val DIAGNOSE_ID_KOPIEREN_TAG = "diagnose_id_kopieren"
const val DIAGNOSE_VERSIONSKENNUNG_TEXT_TAG = "diagnose_versionskennung_text"
const val DIAGNOSE_VERSIONSKENNUNG_KOPIEREN_TAG = "diagnose_versionskennung_kopieren"

/**
 * M12 Schritt 7 (Konzept Abschnitt 2): Startobergrenze fuer [DiagnosticLogDao.neueste] - ersetzt
 * die vormalige unbegrenzte `alle()`-Abfrage, die bei jeder neuen Zeile im Aufzeichnungsbetrieb
 * die komplette, mit der Zeit beliebig lange Tabelle neu in den Heap zog (siehe Konzept-Verdacht
 * fuer den vom Owner gemeldeten Absturz). "Weitere laden" erhoeht sie um denselben Schritt.
 */
private const val DIAGNOSE_LOG_GRENZE_SCHRITT = 200

/** M12 Schritt 8 (Konzept Aufgabe 1): Anzahl der noch nicht hochgeladenen Bundles in `support_outbox/`. */
private fun zaehleSupportOutbox(context: Context): Int =
    File(context.filesDir, com.example.lrmprotokoll.diagnose.acra.SUPPORT_OUTBOX_DIR)
        .listFiles { f -> f.isFile && f.name.endsWith(".zip") }?.size ?: 0

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnoseScreen(
    onBack: () -> Unit,
    onShowSnackbar: ((String) -> Unit)? = null,
    onNavigateToSettings: ((tab: String?) -> Unit)? = null,
    onNavigateToMeter: (() -> Unit)? = null,
    exakteAlarmeErlaubtOverride: Boolean? = null,
) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as LaermprotokollApp).container }
    val supervisor = container.connectionSupervisor
    val transport = container.meterTransport
    val scope = rememberCoroutineScope()

    val verbindungszustand by supervisor.state.collectAsState()
    val frameQuality by transport.frameQuality.collectAsState()
    var diagnoseLogGrenze by remember { mutableStateOf(DIAGNOSE_LOG_GRENZE_SCHRITT) }
    val diagnoseLog by remember(diagnoseLogGrenze) {
        container.database.diagnosticLogDao().neueste(diagnoseLogGrenze)
    }.collectAsState(initial = emptyList())
    val syncHistorie by container.database.driveDailyFileDao().alle().collectAsState(initial = emptyList())
    val alarmHistorie by container.database.alertDao().alle().collectAsState(initial = emptyList())

    var remoteDiagnoseAktiv by remember { mutableStateOf(container.settingsManager.remoteDiagnoseAktiv) }
    var letzteDiagnoseId by remember { mutableStateOf(container.settingsManager.letzteDiagnoseId) }
    var reconnectZaehler by remember { mutableStateOf(0) }
    var exportiertGerade by remember { mutableStateOf(false) }
    var isSyncing by remember { mutableStateOf(false) }
    var driveMessage by remember { mutableStateOf(container.settingsManager.driveSyncLastMessage) }

    // M12 Schritt 8 (Konzept Aufgabe 1): Sichtbarkeit des Support-Bundle-Uploads.
    var supportBundleLastUploadAt by remember { mutableStateOf(container.settingsManager.supportBundleLastUploadAt) }
    var supportBundleLastUploadMessage by remember { mutableStateOf(container.settingsManager.supportBundleLastUploadMessage) }
    var supportBundleOutboxAnzahl by remember { mutableStateOf(0) }
    var supportBundleAktionLaeuft by remember { mutableStateOf(false) }

    val alarmManager = remember { context.getSystemService(android.app.AlarmManager::class.java) }

    fun kannExakteAlarme(): Boolean =
        exakteAlarmeErlaubtOverride ?: if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            alarmManager?.canScheduleExactAlarms() == true
        } else {
            true
        }

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }
    var hasNotificationPermission by remember {
        mutableStateOf(
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            } else {
                true
            },
        )
    }
    var hasBluetoothPermission by remember {
        mutableStateOf(BluetoothPermissions.hasPermissions(context))
    }
    var canScheduleExactAlarms by remember {
        mutableStateOf(kannExakteAlarme())
    }
    val isBluetoothAdapterEnabled by container.bluetoothAdapterStateObserver.enabled.collectAsState()

    val audioPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            hasAudioPermission = granted
        }
    val bluetoothPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) {
            hasBluetoothPermission = BluetoothPermissions.hasPermissions(context)
        }
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            hasNotificationPermission = granted
        }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    hasAudioPermission =
                        ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    hasNotificationPermission =
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
                                android.content.pm.PackageManager.PERMISSION_GRANTED
                        } else {
                            true
                        }
                    hasBluetoothPermission = BluetoothPermissions.hasPermissions(context)
                    canScheduleExactAlarms = kannExakteAlarme()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun appDetailsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }

    /**
     * Oeffnet den ersten Systemdialog, den das Geraet beantwortet.
     *
     * Nicht jedes Geraet kennt jeden Settings-Intent, deshalb die Kandidatenliste. Scheitert
     * JEDER Weg, darf das nicht still passieren: Sonst tippt der Nutzer auf "Beheben" und es
     * geschieht sichtbar nichts. Genau diese Fehlerklasse hat Phase 1 mit F-27 fuer die
     * Exportwege beseitigt; sie gehoert hier nicht wieder eingefuehrt.
     */
    fun oeffneErsteErreichbare(
        beschreibung: String,
        vararg kandidaten: Intent,
    ) {
        for (intent in kandidaten) {
            try {
                context.startActivity(intent)
                return
            } catch (_: Exception) {
                // Naechsten Kandidaten versuchen.
            }
        }
        container.diagnosticsReporter.breadcrumb(
            "Diagnose",
            "Kein Systemdialog erreichbar fuer: $beschreibung",
        )
        val meldung = "Die Einstellungen für „$beschreibung\" lassen sich auf diesem Gerät nicht öffnen."
        if (onShowSnackbar != null) {
            onShowSnackbar(meldung)
        } else {
            Toast.makeText(context, meldung, Toast.LENGTH_LONG).show()
        }
    }

    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
    val isBatteryOptimizationIgnored = powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    val dienstAktiv by AudioRecordingService.laeuft.collectAsState()

    val healthOverview =
        remember(
            hasAudioPermission,
            hasNotificationPermission,
            hasBluetoothPermission,
            isBatteryOptimizationIgnored,
            canScheduleExactAlarms,
            isBluetoothAdapterEnabled,
            verbindungszustand,
            dienstAktiv,
        ) {
            bewerteSystemZustand(
                SystemHealthParams(
                    hasAudioPermission = hasAudioPermission,
                    hasNotificationPermission = hasNotificationPermission,
                    hasBluetoothPermission = hasBluetoothPermission,
                    isBatteryOptimizationIgnored = isBatteryOptimizationIgnored,
                    canScheduleExactAlarms = canScheduleExactAlarms,
                    isBluetoothAdapterEnabled = isBluetoothAdapterEnabled,
                    isMeterPinned = container.settingsManager.meterDeviceAddress != null,
                    meterConnectionState = verbindungszustand,
                    isAlertingConfigured = container.settingsManager.alarmierungAktiv,
                    isDriveSyncConfigured = container.settingsManager.driveSyncEnabled,
                    isDiagnoseLoggingActive = container.settingsManager.diagnoseLoggingAktiv,
                    isMonitoringActive = dienstAktiv,
                ),
            )
        }

    LaunchedEffect(Unit) {
        val db = container.database
        val session = db.sessionDao().letzte()
        if (session != null) {
            reconnectZaehler = zaehleReconnects(db.connectionEventDao().fuerSession(session.id))
        }
        supportBundleOutboxAnzahl = withContext(Dispatchers.IO) { zaehleSupportOutbox(context) }
    }

    val fehlerrateProzent = if (frameQuality.totalFrames > 0) {
        frameQuality.errorFrames * 100.0 / frameQuality.totalFrames
    } else {
        0.0
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_diagnose)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    BluetoothStatusBadge(
                        state = verbindungszustand,
                        deviceName = container.settingsManager.meterDeviceName,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
                .testTag(DIAGNOSE_LAZY_COLUMN_TAG)
        ) {
            // Sektion: F3 System-Selbstprüfung Checkliste
            item {
                Text(stringResource(R.string.diagnose_self_check_header), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        healthOverview.items.forEach { checkItem ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Icon(
                                        imageVector = when (checkItem.status) {
                                            HealthStatus.OK -> Icons.Default.Check
                                            HealthStatus.WARNING -> Icons.Default.Warning
                                            HealthStatus.ERROR -> Icons.Default.Close
                                        },
                                        contentDescription = null,
                                        tint = when (checkItem.status) {
                                            HealthStatus.OK -> MaterialTheme.colorScheme.statusColors.connected
                                            HealthStatus.WARNING -> MaterialTheme.colorScheme.statusColors.warning
                                            HealthStatus.ERROR -> MaterialTheme.colorScheme.error
                                        },
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(checkItem.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                        Text(checkItem.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }

                                checkItem.actionLabel?.let { label ->
                                    TextButton(
                                        onClick = {
                                            when (checkItem.actionType) {
                                                HealthActionType.REQUEST_AUDIO_PERMISSION -> {
                                                    audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                                }
                                                HealthActionType.REQUEST_BLUETOOTH_PERMISSION -> {
                                                    bluetoothPermissionLauncher.launch(BluetoothPermissions.requiredPermissions())
                                                }
                                                HealthActionType.REQUEST_NOTIFICATION_PERMISSION -> {
                                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                                        notificationPermissionLauncher.launch(
                                                            android.Manifest.permission.POST_NOTIFICATIONS,
                                                        )
                                                    } else {
                                                        oeffneErsteErreichbare("Benachrichtigungen", appDetailsIntent())
                                                    }
                                                }
                                                HealthActionType.BATTERY_OPTIMIZATION -> {
                                                    oeffneErsteErreichbare(
                                                        "Akku-Optimierung",
                                                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                                            data = Uri.parse("package:${context.packageName}")
                                                        },
                                                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                                                        appDetailsIntent(),
                                                    )
                                                }
                                                HealthActionType.EXACT_ALARM_PERMISSION -> {
                                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                                                        oeffneErsteErreichbare(
                                                            "Exakte Alarme",
                                                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                                                data = Uri.parse("package:${context.packageName}")
                                                            },
                                                            appDetailsIntent(),
                                                        )
                                                    }
                                                }
                                                HealthActionType.ENABLE_BLUETOOTH -> {
                                                    oeffneErsteErreichbare(
                                                        "Bluetooth",
                                                        Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
                                                        Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE),
                                                        appDetailsIntent(),
                                                    )
                                                }
                                                HealthActionType.CONNECT_METER -> {
                                                    onNavigateToMeter?.invoke()
                                                }
                                                HealthActionType.CONFIGURE_ALERTING -> {
                                                    onNavigateToSettings?.invoke(SettingsTab.START.routeArg)
                                                }
                                                HealthActionType.CONFIGURE_DRIVE -> {
                                                    onNavigateToSettings?.invoke(SettingsTab.DATEN.routeArg)
                                                }
                                                HealthActionType.OPEN_SETTINGS -> {
                                                    onNavigateToSettings?.invoke(null)
                                                }
                                                null -> {
                                                    oeffneErsteErreichbare("App-Einstellungen", appDetailsIntent())
                                                }
                                            }
                                        },
                                        modifier = Modifier.testTag("health_action_${checkItem.id}"),
                                    ) {
                                        Text(label)
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                OemDeviceHelperCard()
                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                Text(stringResource(R.string.diagnose_state_header), style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                Text(verbindungszustand.label(), style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.diagnose_reconnects, reconnectZaehler),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.diagnose_decode_error_rate, fehlerrateProzent, frameQuality.errorFrames, frameQuality.totalFrames),
                    style = MaterialTheme.typography.bodyMedium,
                )

                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                Text(stringResource(R.string.diagnose_remote_privacy_header), style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(stringResource(R.string.diagnose_send_reports), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    stringResource(R.string.diagnose_send_reports_desc),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Switch(
                                checked = remoteDiagnoseAktiv,
                                onCheckedChange = { aktiv ->
                                    remoteDiagnoseAktiv = aktiv
                                    container.settingsManager.remoteDiagnoseAktiv = aktiv
                                }
                            )
                        }

                        if (letzteDiagnoseId != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(stringResource(R.string.diagnose_last_id_label), style = MaterialTheme.typography.labelSmall)
                                    Text(letzteDiagnoseId ?: "", style = MaterialTheme.typography.bodyMedium)
                                }
                                OutlinedButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("Diagnose-ID", letzteDiagnoseId))
                                        Toast.makeText(context, "Diagnose-ID in Zwischenablage kopiert", Toast.LENGTH_SHORT).show()
                                    },
                                    // Seit der Versionskennung (docs/PROMPT_VERSIONSKENNUNG.md
                                    // Abschnitt 4.5) gibt es zwei "Kopieren"-Knoepfe in dieser
                                    // Karte - testTag macht diesen hier eindeutig ansprechbar.
                                    modifier = Modifier.testTag(DIAGNOSE_ID_KOPIEREN_TAG),
                                ) {
                                    Text(stringResource(R.string.action_copy))
                                }
                            }
                        }

                        // Saubere Versionskennung (docs/PROMPT_VERSIONSKENNUNG.md Abschnitt 4.5):
                        // im Support-Fall braucht man Diagnose-ID UND Versionskennung zusammen,
                        // deshalb in derselben Karte, gleiches Kopier-Verhalten wie oben.
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(8.dp))
                        VersionskennungZeile(
                            kennung =
                                Versionskennung.formatiere(
                                    BuildConfig.VERSION_NAME,
                                    BuildConfig.VERSION_CODE,
                                ),
                            textTag = DIAGNOSE_VERSIONSKENNUNG_TEXT_TAG,
                            kopierenTag = DIAGNOSE_VERSIONSKENNUNG_KOPIEREN_TAG,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        scope.launch {
                            exportiertGerade = true
                            try {
                                val zipFile = withContext(Dispatchers.IO) {
                                    container.supportBundleExporter.createBundle(
                                        com.example.lrmprotokoll.diagnose.export.BundleKontext(
                                            typ = com.example.lrmprotokoll.diagnose.export.BundleTyp.MANUELL,
                                            ausloeser = "Nutzer (DiagnoseScreen)",
                                        )
                                    )
                                }
                                val shareIntent = container.supportBundleExporter.createShareIntent(zipFile)
                                context.startActivity(Intent.createChooser(shareIntent, "Support-Bundle teilen…"))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // Owner-Freigabe 23.09.2026: wie beim Sofortupload unten - Log und
                                // Report-Event statt nur eines Toasts, Hinweis ueber onShowSnackbar.
                                Log.w("DiagnoseScreen", "Support-Bundle-Export fehlgeschlagen", e)
                                container.diagnosticsReporter.report(
                                    code = DiagnosticCode.SUPPORT_BUNDLE_FAILED,
                                    component = "DiagnoseScreen",
                                    operation = "supportBundleExport",
                                    severity = DiagnosticSeverity.ERROR,
                                    cause = e,
                                    message = "Support-Bundle konnte nicht erstellt oder geteilt werden",
                                )
                                val msg = "Export fehlgeschlagen: ${e.message}"
                                onShowSnackbar?.invoke(msg) ?: Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            } finally {
                                exportiertGerade = false
                            }
                        }
                    },
                    enabled = !exportiertGerade,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (exportiertGerade) stringResource(R.string.diagnose_creating_bundle) else stringResource(R.string.diagnose_export_bundle))
                }

                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                Text(stringResource(R.string.diagnose_support_bundles_header), style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            if (supportBundleLastUploadAt > 0) {
                                val formatierer = remember { SimpleDateFormat("dd.MM. HH:mm:ss", Locale.getDefault()) }
                                stringResource(R.string.diagnose_support_bundles_last_upload, formatierer.format(supportBundleLastUploadAt))
                            } else {
                                stringResource(R.string.diagnose_support_bundles_last_upload_never)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (supportBundleLastUploadMessage.isNotBlank()) {
                            Text(
                                supportBundleLastUploadMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.diagnose_support_bundles_outbox_count, supportBundleOutboxAnzahl),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    supportBundleAktionLaeuft = true
                                    try {
                                        withContext(Dispatchers.IO) {
                                            val bundleDatei = container.supportBundleExporter.createBundle(
                                                com.example.lrmprotokoll.diagnose.export.BundleKontext(
                                                    typ = com.example.lrmprotokoll.diagnose.export.BundleTyp.MANUELL,
                                                    ausloeser = "Nutzer (DiagnoseScreen, Support-Bundles-Sofortupload)",
                                                )
                                            )
                                            val outboxDir = File(context.filesDir, com.example.lrmprotokoll.diagnose.acra.SUPPORT_OUTBOX_DIR).apply { mkdirs() }
                                            val ziel = File(outboxDir, bundleDatei.name)
                                            bundleDatei.copyTo(ziel, overwrite = true)
                                            bundleDatei.delete()
                                        }
                                        // Manuelle Aktion - laeuft unabhaengig vom Absturz-Auto-Upload-Schalter
                                        // (Einstellungen), der nur den automatischen Pfad betrifft.
                                        com.example.lrmprotokoll.diagnose.export.SupportBundleUploadPlanung.planeSofort(context)
                                        supportBundleOutboxAnzahl = withContext(Dispatchers.IO) { zaehleSupportOutbox(context) }
                                        val msg = context.getString(R.string.diagnose_support_bundles_upload_queued)
                                        onShowSnackbar?.invoke(msg) ?: Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        // Owner-Freigabe 23.09.2026 (Folge-PR zu #182): bisher nur ein
                                        // Toast - die Ausnahme selbst stand nirgends (unter Robolectric
                                        // warf der Toast sogar selbst und verdeckte sie). Jetzt Log und
                                        // Report-Event, Hinweis ueber denselben Weg wie im Erfolgsfall.
                                        Log.w("DiagnoseScreen", "Support-Bundle-Sofortupload fehlgeschlagen", e)
                                        container.diagnosticsReporter.report(
                                            code = DiagnosticCode.SUPPORT_BUNDLE_FAILED,
                                            component = "DiagnoseScreen",
                                            operation = "supportBundleSofortupload",
                                            severity = DiagnosticSeverity.ERROR,
                                            cause = e,
                                            message = "Support-Bundle konnte nicht erstellt oder eingereiht werden",
                                        )
                                        val msg = "Fehlgeschlagen: ${e.message}"
                                        onShowSnackbar?.invoke(msg) ?: Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    } finally {
                                        supportBundleAktionLaeuft = false
                                    }
                                }
                            },
                            enabled = !supportBundleAktionLaeuft,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (supportBundleAktionLaeuft) stringResource(R.string.diagnose_creating_bundle) else stringResource(R.string.diagnose_support_bundles_create_and_upload))
                        }
                    }
                }

                if (BuildConfig.DEBUG) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            val id = container.diagnosticsReporter.report(
                                code = DiagnosticCode.ALERT_LOCAL_FAILED,
                                component = "DiagnoseScreen",
                                operation = "manualTest",
                                severity = DiagnosticSeverity.WARN,
                                message = "Manueller Testbericht durch Benutzer ausgelöst",
                                details = mapOf("source" to "debug_button")
                            )
                            letzteDiagnoseId = id.shortCode
                            container.settingsManager.letzteDiagnoseId = id.shortCode
                            Toast.makeText(context, "Test-Event gesendet ($id)", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Test-Diagnose-Event auslösen")
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    CrashTriggerButtons()
                }

                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                Text(stringResource(R.string.diagnose_log_header, diagnoseLog.size), style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                if (diagnoseLog.isEmpty()) {
                    Text(
                        stringResource(R.string.diagnose_log_empty),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(diagnoseLog) { eintrag -> DiagnoseLogZeile(eintrag) }
            if (diagnoseLog.size >= diagnoseLogGrenze) {
                // Geladene Menge erreicht die aktuelle Grenze - es koennten weitere, aeltere
                // Eintraege existieren (M12 Schritt 7).
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { diagnoseLogGrenze += DIAGNOSE_LOG_GRENZE_SCHRITT },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.diagnose_log_load_more))
                    }
                }
            }

            // Sektion: Alarm-Historie (F15)
            item {
                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                Text(stringResource(R.string.diagnose_alert_history_header, alarmHistorie.size), style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                if (alarmHistorie.isEmpty()) {
                    Text(
                        stringResource(R.string.diagnose_alert_history_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(alarmHistorie) { alarm ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        val formatierer = SimpleDateFormat("dd.MM. HH:mm:ss", Locale.getDefault())
                        Text(
                            text = "${formatierer.format(alarm.outageSince)} · ${alarm.reason}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Status: ${com.example.lrmprotokoll.alert.AlertMessages.zustandsAnzeige(alarm.deliveryState)} · " +
                                "Versuche: ${alarm.attempts} · Empfänger: ${alarm.recipients}" +
                                (if (alarm.resolvedAt != null) " · Entwarnt: ${formatierer.format(alarm.resolvedAt)}" else ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Sektion: Sync-Historie & Google Drive Status
            item {
                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                Text(stringResource(R.string.diagnose_drive_sync_header), style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                DriveStatusCard(
                    googleAccountEmail = container.settingsManager.googleAccountEmail,
                    googleAccountName = container.settingsManager.googleAccountName,
                    syncEnabled = container.settingsManager.driveSyncEnabled,
                    folderName = container.settingsManager.driveFolderName,
                    folderId = container.settingsManager.driveFolderId,
                    isFolderBlocked = container.settingsManager.driveOrdnerBlockiert,
                    consecutiveFailures = container.settingsManager.driveSyncFehlschlaegeInFolge,
                    lastSuccessAt = container.settingsManager.driveSyncLastSuccessAt,
                    lastMessage = driveMessage ?: container.settingsManager.driveSyncLastMessage,
                    latestDailyFile = syncHistorie.firstOrNull(),
                    datenbankSicherungAktiv = container.settingsManager.datenbankSicherungDriveUpload,
                    datenbankSicherungLastSuccessAt = container.settingsManager.datenbankSicherungLastSuccessAt,
                    isSyncing = isSyncing,
                    onToggleSync = { enabled ->
                        container.settingsManager.driveSyncEnabled = enabled
                        if (enabled) DriveSyncPlanung.plane(context) else DriveSyncPlanung.stoppe(context)
                    },
                    onSyncNow = {
                        scope.launch {
                            isSyncing = true
                            try {
                                val ergebnis = withContext(Dispatchers.IO) {
                                    container.driveSyncCoordinator.syncEinenZyklus()
                                }
                                val msg = when (ergebnis) {
                                    is DriveSyncCoordinator.SyncErgebnis.Erfolgreich -> "Synchronisation erfolgreich (${ergebnis.zeilen} Zeilen hochgeladen)"
                                    is DriveSyncCoordinator.SyncErgebnis.KeineAenderung -> "Bereits aktuell (keine neuen Messwerte)"
                                    is DriveSyncCoordinator.SyncErgebnis.Fehlgeschlagen -> "Fehlgeschlagen: ${ergebnis.grund}"
                                    is DriveSyncCoordinator.SyncErgebnis.OrdnerNichtGefunden -> "Ordner nicht gefunden"
                                    is DriveSyncCoordinator.SyncErgebnis.OrdnerBlockiert -> "Ordner blockiert"
                                    is DriveSyncCoordinator.SyncErgebnis.KeinOrdnerEingerichtet -> "Kein Ordner eingerichtet"
                                    is DriveSyncCoordinator.SyncErgebnis.SyncAusgeschaltet -> "Sync pausiert"
                                }
                                driveMessage = msg
                                onShowSnackbar?.invoke(msg) ?: Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            } finally {
                                isSyncing = false
                            }
                        }
                    },
                    onConnectGoogle = {
                        val msg = "Bitte in den Einstellungen mit Google verbinden"
                        onShowSnackbar?.invoke(msg) ?: Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    },
                    onDisconnectGoogle = {
                        container.driveAccessTokenProvider.abmelden()
                        DriveSyncPlanung.stoppe(context)
                        driveMessage = "Google-Konto getrennt"
                    },
                    onUpdateFolderName = { newFolder ->
                        container.settingsManager.driveFolderName = newFolder
                    }
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(stringResource(R.string.diagnose_daily_files_header, syncHistorie.size), style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                if (syncHistorie.isEmpty()) {
                    Text(
                        stringResource(R.string.diagnose_daily_files_empty),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(syncHistorie) { tag -> SyncHistorieZeile(tag) }
        }
    }
}

/**
 * Die drei Testabsturz-Ausloeser (M12 Schritt 1) - eigene, wiederverwendbare Komponente statt
 * inline in [DiagnoseScreen]: [CrashProbeActivity] (`app/src/debug`, M12 Schritt 8 CI-Fund
 * 22.09.2026, PR #182) rendert NUR diese Buttons in einem eigenen Prozess (`:crashprobe`) statt
 * des gesamten DiagnoseScreens. Grund: der instrumentierte Absturztest fand die Buttons dort
 * zuverlaessig NICHT - anders als Composes eigene `onNodeWithText`/`performScrollToIndex`
 * (die den vollen Semantics-Baum unabhaengig vom aktuellen Sichtbereich abfragen) scrollt
 * UiAutomators `UiScrollable` per echten Swipe-Gesten durch den Accessibility-Baum, der
 * praktisch nur das aktuell Sichtbare/Naheliegende zeigt - innerhalb der einen, sehr hohen
 * LazyColumn-Sektion (Status, Fernwartungs-Karte, Support-Bundles-Karte, dann erst dieser
 * Abschnitt) kam er dort nicht zuverlaessig an. `internal` statt `private`, damit
 * [CrashProbeActivity] (anderes Paket-File, gleiches Paket) sie aufrufen kann.
 */
@Composable
internal fun CrashTriggerButtons() {
    Text("Testabsturz (M12 Schritt 1)", style = MaterialTheme.typography.labelMedium)
    Spacer(modifier = Modifier.height(4.dp))
    OutlinedButton(
        onClick = { throw RuntimeException("Testabsturz (Debug): ACRA-Kette pruefen") },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("RuntimeException auslösen")
    }
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedButton(
        onClick = {
            // Bewusst direkt geworfen statt tatsaechlich Speicher vollzuschaufeln:
            // ACRA faengt jeden Throwable gleich ab, ein echter Allokationssturm
            // waere nur langsamer und riskanter (Emulator/Geraet destabilisieren),
            // ohne die Kette Absturz -> Bundle -> Drive anders zu pruefen.
            throw OutOfMemoryError("Testabsturz (Debug): ACRA-Kette pruefen (OOM)")
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("OutOfMemoryError provozieren")
    }
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedButton(
        onClick = {
            // Blockiert absichtlich den Main-Thread, um einen ANR auszuloesen -
            // ohne diesen Ausloeser ist die Kette Absturz -> Bundle -> Drive in
            // keinem der folgenden M12-Schritte am Stueck pruefbar (Konzept
            // Abschnitt 7).
            Thread.sleep(30_000)
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Main-Thread blockieren (ANR)")
    }
}

/** Kopier-Muster fuer die Versionskennung (docs/PROMPT_VERSIONSKENNUNG.md Abschnitt 4.5) - dasselbe
 * wie bei der Diagnose-ID oben, als eigene Funktion, weil der umgebende Kontext hier schon tief
 * verschachtelt ist. */
@Composable
private fun VersionskennungZeile(
    kennung: String,
    textTag: String,
    kopierenTag: String,
) {
    val context = LocalContext.current
    val kopierteNachricht = stringResource(R.string.diagnose_version_copied)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(stringResource(R.string.diagnose_version_label), style = MaterialTheme.typography.labelSmall)
            Text(kennung, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(textTag))
        }
        OutlinedButton(
            onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Versionskennung", kennung))
                Toast.makeText(context, kopierteNachricht, Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.testTag(kopierenTag),
        ) {
            Text(stringResource(R.string.action_copy))
        }
    }
}

@Composable
private fun DiagnoseLogZeile(eintrag: DiagnosticLogEntity) {
    val formatierer = remember { SimpleDateFormat("dd.MM. HH:mm:ss", Locale.getDefault()) }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(formatierer.format(eintrag.timestamp), style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.height(0.dp))
        Text(" — ${eintrag.message}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SyncHistorieZeile(tag: DriveDailyFileEntity) {
    val farbe = when (tag.state) {
        DriveSyncState.SYNCED -> MaterialTheme.colorScheme.primary
        DriveSyncState.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text("${tag.date} · ${tag.state} · ${tag.lastRowCount} Zeilen", color = farbe, style = MaterialTheme.typography.bodySmall)
        }
    }
}
