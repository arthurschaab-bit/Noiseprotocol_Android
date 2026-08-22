package com.example.lrmprotokoll.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.lrmprotokoll.BuildConfig
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.audio.AudioRecordingService
import com.example.lrmprotokoll.data.DiagnosticLogEntity
import com.example.lrmprotokoll.data.DriveDailyFileEntity
import com.example.lrmprotokoll.data.DriveSyncState
import com.example.lrmprotokoll.diagnose.DiagnosticCode
import com.example.lrmprotokoll.diagnose.DiagnosticSeverity
import com.example.lrmprotokoll.diagnose.HealthStatus
import com.example.lrmprotokoll.diagnose.SystemHealthParams
import com.example.lrmprotokoll.diagnose.bewerteSystemZustand
import com.example.lrmprotokoll.drive.DriveSyncCoordinator
import com.example.lrmprotokoll.drive.DriveSyncPlanung
import com.example.lrmprotokoll.messreihe.zaehleReconnects
import com.example.lrmprotokoll.meter.label
import com.example.lrmprotokoll.ui.theme.statusColors
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Der Diagnose-Screen (Plan Abschnitt 9) - "kein Luxus": bei einer Dauerüberwachung, die
 * alarmiert, muss nachvollziehbar sein, warum ein Alarm ausgelöst wurde oder ausblieb.
 *
 * Enthält Live-Status, Remote-Diagnose, Diagnose-Log, F3 System-Selbstprüfung, F15 Alarm-Historie
 * und Google Drive Sync-Historie.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnoseScreen(
    onBack: () -> Unit,
    onOpenDrawer: (() -> Unit)? = null,
    onShowSnackbar: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as LaermprotokollApp).container }
    val supervisor = container.connectionSupervisor
    val transport = container.meterTransport
    val scope = rememberCoroutineScope()

    val verbindungszustand by supervisor.state.collectAsState()
    val frameQuality by transport.frameQuality.collectAsState()
    val diagnoseLog by container.database.diagnosticLogDao().alle().collectAsState(initial = emptyList())
    val syncHistorie by container.database.driveDailyFileDao().alle().collectAsState(initial = emptyList())
    val alarmHistorie by container.database.alertDao().alle().collectAsState(initial = emptyList())

    var remoteDiagnoseAktiv by remember { mutableStateOf(container.settingsManager.remoteDiagnoseAktiv) }
    var letzteDiagnoseId by remember { mutableStateOf(container.settingsManager.letzteDiagnoseId) }
    var reconnectZaehler by remember { mutableStateOf(0) }
    var exportiertGerade by remember { mutableStateOf(false) }
    var isSyncing by remember { mutableStateOf(false) }
    var driveMessage by remember { mutableStateOf(container.settingsManager.driveSyncLastMessage) }

    val hasAudioPermission = ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
    val hasNotificationPermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    } else true
    val hasBluetoothPermission = ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_SCAN) == android.content.pm.PackageManager.PERMISSION_GRANTED

    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
    val isBatteryOptimizationIgnored = powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    val dienstAktiv by AudioRecordingService.laeuft.collectAsState()

    val healthOverview = remember(hasAudioPermission, hasNotificationPermission, hasBluetoothPermission, isBatteryOptimizationIgnored, verbindungszustand, dienstAktiv) {
        bewerteSystemZustand(
            SystemHealthParams(
                hasAudioPermission = hasAudioPermission,
                hasNotificationPermission = hasNotificationPermission,
                hasBluetoothPermission = hasBluetoothPermission,
                isBatteryOptimizationIgnored = isBatteryOptimizationIgnored,
                canScheduleExactAlarms = true,
                isBluetoothAdapterEnabled = true,
                isMeterPinned = container.settingsManager.meterDeviceAddress != null,
                meterConnectionState = verbindungszustand,
                isAlertingConfigured = container.settingsManager.alarmierungAktiv,
                isDriveSyncConfigured = container.settingsManager.driveSyncEnabled,
                isDiagnoseLoggingActive = container.settingsManager.diagnoseLoggingAktiv,
                isMonitoringActive = dienstAktiv
            )
        )
    }

    LaunchedEffect(Unit) {
        val db = container.database
        val session = db.sessionDao().letzte()
        if (session != null) {
            reconnectZaehler = zaehleReconnects(db.connectionEventDao().fuerSession(session.id))
        }
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
                    if (onOpenDrawer != null) {
                        IconButton(onClick = onOpenDrawer, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.action_menu))
                        }
                    } else {
                        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
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
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            item {
                Text("Zustand", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                Text(verbindungszustand.label(), style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Reconnects (aktuelle/letzte Session): $reconnectZaehler",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Decode-Fehlerrate: ${String.format(Locale.getDefault(), "%.1f", fehlerrateProzent)} % " +
                        "(${frameQuality.errorFrames}/${frameQuality.totalFrames} seit letztem Verbindungsaufbau)",
                    style = MaterialTheme.typography.bodyMedium,
                )

                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                Text("Remote-Diagnose & Datenschutz", style = MaterialTheme.typography.titleSmall)
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
                                Text("Fehlerberichte senden", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "Übermittelt technische Fehlercodes und Absturzberichte (Sentry). Keine Audiodaten, MAC-Adressen und Namen sind pseudonymisiert.",
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
                                    Text("Letzte Diagnose-ID:", style = MaterialTheme.typography.labelSmall)
                                    Text(letzteDiagnoseId ?: "", style = MaterialTheme.typography.bodyMedium)
                                }
                                OutlinedButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("Diagnose-ID", letzteDiagnoseId))
                                        Toast.makeText(context, "Diagnose-ID in Zwischenablage kopiert", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Text("Kopieren")
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        scope.launch {
                            exportiertGerade = true
                            try {
                                val zipFile = withContext(Dispatchers.IO) {
                                    container.supportBundleExporter.createBundle(diagnoseLog)
                                }
                                val shareIntent = container.supportBundleExporter.createShareIntent(zipFile)
                                context.startActivity(Intent.createChooser(shareIntent, "Support-Bundle teilen…"))
                            } catch (e: Exception) {
                                Toast.makeText(context, "Export fehlgeschlagen: ${e.message}", Toast.LENGTH_LONG).show()
                            } finally {
                                exportiertGerade = false
                            }
                        }
                    },
                    enabled = !exportiertGerade,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (exportiertGerade) "Erstelle Support-Bundle…" else "Support-Bundle exportieren (ZIP)")
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
                }

                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                Text("Diagnose-Log (${diagnoseLog.size})", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                if (diagnoseLog.isEmpty()) {
                    Text(
                        "Kein Eintrag - entweder ist alles in Ordnung, oder das Diagnose-Log ist " +
                            "in den Einstellungen ausgeschaltet (Default).",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(diagnoseLog) { eintrag -> DiagnoseLogZeile(eintrag) }

            // Sektion: F3 System-Selbstprüfung Checkliste
            item {
                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                Text("System-Selbstprüfung", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
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
                                    TextButton(onClick = {
                                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.fromParts("package", context.packageName, null)
                                        }
                                        context.startActivity(intent)
                                    }) {
                                        Text(label)
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                OemDeviceHelperCard()
            }

            // Sektion: Alarm-Historie (F15)
            item {
                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                Text("Alarm-Historie (${alarmHistorie.size})", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                if (alarmHistorie.isEmpty()) {
                    Text(
                        "Bisher wurden keine Alarme ausgelöst.",
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
                            text = "Status: ${alarm.deliveryState} · Versuche: ${alarm.attempts} · Empfänger: ${alarm.recipients}" +
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
                Text("Google Drive Synchronisation", style = MaterialTheme.typography.titleSmall)
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
                Text("Tägliche CSV-Dateien (${syncHistorie.size})", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                if (syncHistorie.isEmpty()) {
                    Text(
                        "Noch keine synchronisierten Tagesdateien vorhanden.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(syncHistorie) { tag -> SyncHistorieZeile(tag) }
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
