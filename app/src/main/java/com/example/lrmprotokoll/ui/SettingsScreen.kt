package com.example.lrmprotokoll.ui

import android.app.AlarmManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.lrmprotokoll.AppContainer
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.alert.ChannelId
import com.example.lrmprotokoll.data.SettingsManager
import com.example.lrmprotokoll.data.erzeugeNtfyTopic
import com.example.lrmprotokoll.drive.DriveSyncPlanung
import com.example.lrmprotokoll.drive.auth.AutorisierungBenoetigtException
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenDrawer: (() -> Unit)? = null,
    onShowSnackbar: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as LaermprotokollApp).container }
    val settings = container.settingsManager
    val scope = rememberCoroutineScope()
    val verbindungszustand by container.connectionSupervisor.state.collectAsState()

    // Aufnahme-Parameter
    var dbThreshold by remember { mutableFloatStateOf(settings.dbThreshold) }
    var preRoll by remember { mutableFloatStateOf(settings.preRollSeconds.toFloat()) }
    var duration by remember { mutableFloatStateOf(settings.recordDurationSeconds.toFloat()) }
    var sampleRate by remember { mutableIntStateOf(settings.audioSampleRate) }

    // KI-Parameter
    var aiEnabled by remember { mutableStateOf(settings.aiEnabled) }
    var aiConfidence by remember { mutableFloatStateOf(settings.aiConfidenceThreshold) }

    // F8: Ruhezeiten
    var quietHoursEnabled by remember { mutableStateOf(settings.quietHoursEnabled) }
    var quietHoursStartHour by remember { mutableFloatStateOf(settings.quietHoursStartHour.toFloat()) }
    var quietHoursEndHour by remember { mutableFloatStateOf(settings.quietHoursEndHour.toFloat()) }
    var quietHoursThreshold by remember { mutableFloatStateOf(settings.quietHoursThreshold) }

    // F5: Auto-Bereinigung & Speicherplatz
    var autoRetentionEnabled by remember { mutableStateOf(settings.autoRetentionEnabled) }
    var autoRetentionDays by remember { mutableFloatStateOf(settings.autoRetentionDays.toFloat()) }

    // Alarmierung
    var alarmierungAktiv by remember { mutableStateOf(settings.alarmierungAktiv) }
    var karenzzeit by remember { mutableFloatStateOf(settings.karenzzeitSekunden.toFloat()) }
    var ntfyAktiv by remember { mutableStateOf(settings.ntfyAktiv) }
    var ntfyServer by remember { mutableStateOf(settings.ntfyServer) }
    var ntfyTopic by remember { mutableStateOf(settings.ntfyTopic) }
    var heartbeatUrl by remember { mutableStateOf(settings.heartbeatUrl) }
    var entwarnungNtfy by remember { mutableStateOf(settings.entwarnungUeberNtfy) }
    var entwarnungMeldung by remember { mutableStateOf(settings.entwarnungUeberMeldung) }
    var testErgebnis by remember { mutableStateOf<String?>(null) }

    // Drive Sync
    var driveSyncAktiv by remember { mutableStateOf(settings.driveSyncEnabled) }
    var driveOrdnerName by remember { mutableStateOf(settings.driveFolderName) }
    var driveOrdnerId by remember { mutableStateOf(settings.driveFolderId) }
    var driveOrdnerBlockiert by remember { mutableStateOf(settings.driveOrdnerBlockiert) }
    var driveAggregationSekunden by remember { mutableFloatStateOf(settings.driveAggregationSekunden.toFloat()) }
    var driveWlanOnly by remember { mutableStateOf(settings.driveWlanOnly) }
    var driveUploadWav by remember { mutableStateOf(settings.driveUploadWav) }
    var driveEinrichtungsErgebnis by remember { mutableStateOf<String?>(null) }
    var ausstehendeZustimmung by remember { mutableStateOf<IntentSender?>(null) }

    // Diagnose & Akku
    var diagnoseLoggingAktiv by remember { mutableStateOf(settings.diagnoseLoggingAktiv) }

    // Expandable Sektionszustände
    var expAufnahme by remember { mutableStateOf(false) }
    var expKi by remember { mutableStateOf(false) }
    var expRuhezeiten by remember { mutableStateOf(false) }
    var expRetention by remember { mutableStateOf(false) }
    var expAlarm by remember { mutableStateOf(false) }
    var expDrive by remember { mutableStateOf(false) }
    var expSystem by remember { mutableStateOf(false) }

    suspend fun verarbeiteDriveEinrichtungsVersuch() {
        when (val versuch = versucheDriveEinrichtung(container, settings, driveOrdnerName)) {
            is DriveEinrichtungsVersuch.Erfolg -> {
                driveOrdnerId = versuch.folderId
                driveOrdnerBlockiert = false
                driveEinrichtungsErgebnis = versuch.nachricht
            }
            is DriveEinrichtungsVersuch.Fehler -> driveEinrichtungsErgebnis = versuch.nachricht
            is DriveEinrichtungsVersuch.ZustimmungNoetig -> ausstehendeZustimmung = versuch.intentSender
        }
    }

    val driveZustimmungLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) {
        scope.launch { verarbeiteDriveEinrichtungsVersuch() }
    }

    LaunchedEffect(ausstehendeZustimmung) {
        ausstehendeZustimmung?.let { intentSender ->
            driveZustimmungLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            ausstehendeZustimmung = null
        }
    }

    val alarmManager = remember { context.getSystemService(AlarmManager::class.java) }
    fun kannExakteAlarme() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager?.canScheduleExactAlarms() == true
        } else true
    var exakteAlarmeErlaubt by remember { mutableStateOf(kannExakteAlarme()) }

    val powerManager = remember { context.getSystemService(PowerManager::class.java) }
    fun isIgnoringBatteryOptimizations() =
        powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    var batteryOptimizationIgnored by remember { mutableStateOf(isIgnoringBatteryOptimizations()) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryOptimizationIgnored = isIgnoringBatteryOptimizations()
                exakteAlarmeErlaubt = kannExakteAlarme()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_settings)) },
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
                        deviceName = settings.meterDeviceName,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Sektion 1: Aufnahme & Mikrofon
            SettingsSectionCard(
                title = "Aufnahme & Mikrofon",
                summary = "${String.format(Locale.getDefault(), "%.1f", dbThreshold)} dB Schwelle · ${preRoll.toInt()}s Vorlauf · ${duration.toInt()}s Dauer",
                expanded = expAufnahme,
                onToggle = { expAufnahme = !expAufnahme }
            ) {
                Text("Aufnahme-Schwellenwert (Mikrofon): ${String.format(Locale.getDefault(), "%.1f", dbThreshold)} dB")
                Text(
                    "Gilt nur, solange kein Messgerät verbunden ist. Bei bestehender Messgerät-Verbindung wird durchgehend aufgezeichnet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = dbThreshold,
                    onValueChange = { dbThreshold = it },
                    onValueChangeFinished = { settings.dbThreshold = dbThreshold },
                    valueRange = 30f..100f
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text("Pre-Roll (Sekunden): ${preRoll.toInt()}s")
                Slider(
                    value = preRoll,
                    onValueChange = { preRoll = it },
                    onValueChangeFinished = { settings.preRollSeconds = preRoll.toInt() },
                    valueRange = 0f..5f,
                    steps = 4
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text("Aufnahmedauer (Sekunden): ${duration.toInt()}s")
                Slider(
                    value = duration,
                    onValueChange = { duration = it },
                    onValueChangeFinished = { settings.recordDurationSeconds = duration.toInt() },
                    valueRange = 1f..10f,
                    steps = 8
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text("Abtastrate (Sample Rate): $sampleRate Hz")
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = sampleRate == 16000,
                        onClick = { sampleRate = 16000; settings.audioSampleRate = 16000 },
                        label = { Text("16000 Hz (KI-Opt.)") }
                    )
                    FilterChip(
                        selected = sampleRate == 44100,
                        onClick = { sampleRate = 44100; settings.audioSampleRate = 44100 },
                        label = { Text("44100 Hz (Qualität)") }
                    )
                }
            }

            // Sektion 2: KI-Erkennung
            SettingsSectionCard(
                title = "KI-Erkennung (YAMNet)",
                summary = if (aiEnabled) "Aktiv (${(aiConfidence * 100).toInt()}% Schwelle)" else "Deaktiviert",
                expanded = expKi,
                onToggle = { expKi = !expKi }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Automatische KI-Erkennung", style = MaterialTheme.typography.bodyLarge)
                        Text("Klassifiziert Geräusche automatisch nach der Aufnahme", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = aiEnabled,
                        onCheckedChange = {
                            aiEnabled = it
                            settings.aiEnabled = it
                        }
                    )
                }

                if (aiEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("KI-Vertrauensschwelle: ${(aiConfidence * 100).toInt()}%")
                    Slider(
                        value = aiConfidence,
                        onValueChange = { aiConfidence = it },
                        onValueChangeFinished = { settings.aiConfidenceThreshold = aiConfidence },
                        valueRange = 0.05f..0.95f
                    )
                }
            }

            // Sektion 3: F8 Ruhezeiten & Grenzwerte
            SettingsSectionCard(
                title = "Ruhezeiten & Grenzwerte",
                summary = if (quietHoursEnabled) "Aktiv (${quietHoursStartHour.toInt()}:00 - ${quietHoursEndHour.toInt()}:00 Uhr · ${String.format(Locale.getDefault(), "%.1f", quietHoursThreshold)} dB)" else "Deaktiviert",
                expanded = expRuhezeiten,
                onToggle = { expRuhezeiten = !expRuhezeiten }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Ruhezeiten berücksichtigen", style = MaterialTheme.typography.bodyLarge)
                        Text("Verwendet in den Ruhezeiten einen separaten Schwellenwert", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = quietHoursEnabled,
                        onCheckedChange = {
                            quietHoursEnabled = it
                            settings.quietHoursEnabled = it
                        }
                    )
                }

                if (quietHoursEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Ruhezeit-Schwellenwert: ${String.format(Locale.getDefault(), "%.1f", quietHoursThreshold)} dB")
                    Slider(
                        value = quietHoursThreshold,
                        onValueChange = { quietHoursThreshold = it },
                        onValueChangeFinished = { settings.quietHoursThreshold = quietHoursThreshold },
                        valueRange = 30f..80f
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Start: ${quietHoursStartHour.toInt()}:00 Uhr")
                    Slider(
                        value = quietHoursStartHour,
                        onValueChange = { quietHoursStartHour = it },
                        onValueChangeFinished = { settings.quietHoursStartHour = quietHoursStartHour.toInt() },
                        valueRange = 0f..23f,
                        steps = 22
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Ende: ${quietHoursEndHour.toInt()}:00 Uhr")
                    Slider(
                        value = quietHoursEndHour,
                        onValueChange = { quietHoursEndHour = it },
                        onValueChangeFinished = { settings.quietHoursEndHour = quietHoursEndHour.toInt() },
                        valueRange = 0f..23f,
                        steps = 22
                    )
                }
            }

            // Sektion 4: F5 Speicherplatz & Auto-Bereinigung
            SettingsSectionCard(
                title = "Speicherplatz & Auto-Bereinigung",
                summary = if (autoRetentionEnabled) "Auto-Bereinigung nach ${autoRetentionDays.toInt()} Tagen" else "Manuell",
                expanded = expRetention,
                onToggle = { expRetention = !expRetention }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Automatische Bereinigung", style = MaterialTheme.typography.bodyLarge)
                        Text("Verschiebt alte Aufnahmen automatisch in den Papierkorb (Favoriten sind geschützt)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = autoRetentionEnabled,
                        onCheckedChange = {
                            autoRetentionEnabled = it
                            settings.autoRetentionEnabled = it
                        }
                    )
                }

                if (autoRetentionEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Aufbewahrungsdauer: ${autoRetentionDays.toInt()} Tage")
                    Slider(
                        value = autoRetentionDays,
                        onValueChange = { autoRetentionDays = it },
                        onValueChangeFinished = { settings.autoRetentionDays = autoRetentionDays.toInt() },
                        valueRange = 7f..180f
                    )
                }
            }

            // Sektion 5: Alarmierung bei Verbindungsabbruch
            SettingsSectionCard(
                title = "Alarmierung bei Verbindungsabbruch",
                summary = if (alarmierungAktiv) "Aktiv (Karenzzeit ${karenzzeit.toInt()}s)" else "Deaktiviert",
                expanded = expAlarm,
                onToggle = { expAlarm = !expAlarm }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = alarmierungAktiv,
                        onCheckedChange = { alarmierungAktiv = it; settings.alarmierungAktiv = it },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Alarmierung aktiv")
                }

                if (alarmierungAktiv) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Karenzzeit: ${karenzzeit.toInt()} s")
                    Slider(
                        value = karenzzeit,
                        onValueChange = { karenzzeit = it },
                        onValueChangeFinished = { settings.karenzzeitSekunden = karenzzeit.toInt() },
                        valueRange = 10f..900f,
                    )

                    if (!exakteAlarmeErlaubt) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Ohne Berechtigung für exakte Alarme kann die Karenzzeit verzögert ablaufen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                            }
                        }) {
                            Text("Exakte Alarme erlauben")
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Push auf ein zweites Gerät (ntfy)", style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = ntfyAktiv,
                            onCheckedChange = {
                                ntfyAktiv = it
                                settings.ntfyAktiv = it
                                if (it && ntfyTopic.isBlank()) {
                                    ntfyTopic = erzeugeNtfyTopic()
                                    settings.ntfyTopic = ntfyTopic
                                }
                            },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("ntfy-Push aktiv")
                    }

                    if (ntfyAktiv) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = ntfyServer,
                            onValueChange = { ntfyServer = it; settings.ntfyServer = it },
                            label = { Text("Server") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = ntfyTopic,
                            onValueChange = { ntfyTopic = it; settings.ntfyTopic = it },
                            label = { Text("Topic") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                val alert = com.example.lrmprotokoll.alert.Alert(
                                    alertId = 0,
                                    kind = com.example.lrmprotokoll.alert.AlertKind.TEST,
                                    reason = com.example.lrmprotokoll.alert.AlertReason.DISCONNECTED,
                                    since = java.time.Instant.now(),
                                    message = "Test-Alarm: Verbindung zum Messgerät unterbrochen."
                                )
                                val res = com.example.lrmprotokoll.alert.local.LocalNotificationAlertChannel(context).send(alert)
                                testErgebnis = if (res.isSuccess) "Test-Alarm auf Gerät ausgelöst" else "Fehlgeschlagen: ${res.exceptionOrNull()?.message}"
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Test-Alarm auslösen")
                    }
                    testErgebnis?.let {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // Sektion 6: Google Drive Synchronisation
            SettingsSectionCard(
                title = "Google Drive Synchronisation",
                summary = if (driveSyncAktiv) "Aktiv (Ordner: $driveOrdnerName)" else "Deaktiviert",
                expanded = expDrive,
                onToggle = { expDrive = !expDrive }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = driveSyncAktiv,
                        onCheckedChange = {
                            driveSyncAktiv = it
                            settings.driveSyncEnabled = it
                            if (it) DriveSyncPlanung.plane(context) else DriveSyncPlanung.stoppe(context)
                        },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Drive-Sync aktiv")
                }

                if (driveSyncAktiv) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = driveOrdnerName,
                        onValueChange = { driveOrdnerName = it; settings.driveFolderName = it },
                        label = { Text("Ordnername") },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { scope.launch { verarbeiteDriveEinrichtungsVersuch() } },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Mit Google Drive verbinden")
                    }

                    driveEinrichtungsErgebnis?.let {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = driveWlanOnly,
                            onCheckedChange = {
                                driveWlanOnly = it
                                settings.driveWlanOnly = it
                                DriveSyncPlanung.plane(context)
                            },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Nur über WLAN synchronisieren")
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = driveUploadWav,
                            onCheckedChange = { driveUploadWav = it; settings.driveUploadWav = it },
                        )
                        Text("Audioaufnahmen (WAV) hochladen")
                    }
                }
            }

            // Sektion 7: System & Energie
            SettingsSectionCard(
                title = "System, Logging & Energie",
                summary = if (batteryOptimizationIgnored) "Akku-Optimierung ausgenommen" else "Eingeschränkt",
                expanded = expSystem,
                onToggle = { expSystem = !expSystem }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = diagnoseLoggingAktiv,
                        onCheckedChange = {
                            diagnoseLoggingAktiv = it
                            settings.diagnoseLoggingAktiv = it
                            if (it) {
                                com.example.lrmprotokoll.diagnose.DiagnosticLogCleanupPlanung.plane(context)
                            } else {
                                com.example.lrmprotokoll.diagnose.DiagnosticLogCleanupPlanung.stoppe(context)
                            }
                        },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Diagnose-Log aktiv")
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text("Akku-Optimierung", style = MaterialTheme.typography.titleSmall)
                if (batteryOptimizationIgnored) {
                    Text(
                        "Diese App ist von der Akku-Optimierung ausgenommen. Die Hintergrund-Überwachung läuft zuverlässig.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text(
                        "Ohne Ausnahme kann das Betriebssystem die Verbindung im Hintergrund trennen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = {
                        val intent = Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    }) {
                        Text("Akku-Optimierung deaktivieren")
                    }
                }
            }

            Spacer(modifier = Modifier.fillMaxWidth().height(8.dp).testTag(BILDSCHIRM_ENDE_TAG))
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() }
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    content()
                }
            }
        }
    }
}

const val BILDSCHIRM_ENDE_TAG = "settings_bildschirm_ende"

internal fun formatiereDriveFehler(fehler: Throwable): String {
    val ursache = fehler.cause?.message
    return if (ursache != null) {
        "Verbindung fehlgeschlagen: ${fehler.message} ($ursache)"
    } else {
        "Verbindung fehlgeschlagen: ${fehler.message}"
    }
}

internal sealed interface DriveEinrichtungsVersuch {
    data class Erfolg(val nachricht: String, val folderId: String?) : DriveEinrichtungsVersuch
    data class Fehler(val nachricht: String) : DriveEinrichtungsVersuch
    data class ZustimmungNoetig(val intentSender: IntentSender) : DriveEinrichtungsVersuch
}

internal suspend fun versucheDriveEinrichtung(
    container: AppContainer,
    settings: SettingsManager,
    ordnerName: String,
): DriveEinrichtungsVersuch {
    val name = ordnerName.ifBlank { "Lärmprotokoll" }
    return container.driveEinrichtung.richteEin(name).fold(
        onSuccess = {
            DriveEinrichtungsVersuch.Erfolg(
                nachricht = "Verbunden. Ordner \"$name\" wurde angelegt.",
                folderId = settings.driveFolderId,
            )
        },
        onFailure = { fehler ->
            val zustimmung = findeAutorisierungBenoetigt(fehler)
            if (zustimmung != null) {
                DriveEinrichtungsVersuch.ZustimmungNoetig(zustimmung.intentSender)
            } else {
                DriveEinrichtungsVersuch.Fehler(formatiereDriveFehler(fehler))
            }
        },
    )
}

internal tailrec fun findeAutorisierungBenoetigt(fehler: Throwable?): AutorisierungBenoetigtException? =
    when (fehler) {
        null -> null
        is AutorisierungBenoetigtException -> fehler
        else -> findeAutorisierungBenoetigt(fehler.cause)
    }
