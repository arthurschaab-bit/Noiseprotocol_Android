package com.example.lrmprotokoll.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.audio.ACTION_STOP_SERVICE
import com.example.lrmprotokoll.audio.AudioRecordingService
import com.example.lrmprotokoll.audio.EXTRA_START_AUDIO_MONITORING
import com.example.lrmprotokoll.data.MeasurementEntity
import com.example.lrmprotokoll.data.MinuteAggregateEntity
import com.example.lrmprotokoll.data.NoiseRecord
import com.example.lrmprotokoll.data.SessionEntity
import com.example.lrmprotokoll.meter.ConnectionState
import com.example.lrmprotokoll.messreihe.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

const val START_MEASUREMENT_BUTTON_TAG = "start_measurement_button"
const val END_MEASUREMENT_BUTTON_TAG = "end_measurement_button"
const val MARK_NOISE_EVENT_BUTTON_TAG = "mark_noise_event_button"

/**
 * Modernes Cockpit für den Startscreen (Idle & Live-Messungs-Zustand)
 * exakt nach dem neuen Designer-Layout (Screens 1 & 2).
 */
@Composable
fun LiveCockpitCard(
    modifier: Modifier = Modifier,
    onNavigateToSettings: (() -> Unit)? = null,
    onNavigateToDiagnose: (() -> Unit)? = null,
    onNavigateToMeter: (() -> Unit)? = null,
    onShowSnackbar: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as LaermprotokollApp).container }
    val settings = container.settingsManager
    val scope = rememberCoroutineScope()

    val dienstAktiv by AudioRecordingService.laeuft.collectAsState()
    val verbindungszustand by container.connectionSupervisor.state.collectAsState()
    val letzterFrame by container.meterTransport.frames.collectAsState(initial = null)

    val db = container.database
    val letzteSession by db.sessionDao().letzteSessionFlow().collectAsState(initial = null)
    var messwerte by remember { mutableStateOf<List<MeasurementEntity>>(emptyList()) }
    var aggregate by remember { mutableStateOf<List<MinuteAggregateEntity>>(emptyList()) }
    var kennwerte by remember { mutableStateOf<AkustischeKennwerte.Kennwerte?>(null) }
    var ausfallbaender by remember { mutableStateOf<List<Ausfallband>>(emptyList()) }
    var jetzt by remember { mutableLongStateOf(System.currentTimeMillis()) }

    var showMarkNoiseEventSheet by remember { mutableStateOf(false) }

    // Quick-Settings State
    var autoEventDetection by remember { mutableStateOf(settings.aiEnabled) }
    var audioSnippetEnabled by remember { mutableStateOf(settings.driveUploadWav) }

    val hasAudioPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions[Manifest.permission.RECORD_AUDIO] == true) {
            val intent = Intent(context, AudioRecordingService::class.java).apply {
                putExtra(EXTRA_START_AUDIO_MONITORING, true)
            }
            context.startForegroundService(intent)
        } else {
            Toast.makeText(context, "Mikrofon-Berechtigung erforderlich", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(letzteSession?.id) {
        val s = letzteSession
        if (s != null) {
            launch {
                db.measurementDao().fuerSessionFlow(s.id).collectLatest { geladeneMesswerte ->
                    messwerte = geladeneMesswerte
                    if (geladeneMesswerte.isNotEmpty()) {
                        kennwerte = AkustischeKennwerte.berechne(geladeneMesswerte)
                    } else {
                        val geladeneAggregate = db.minuteAggregateDao().fuerSession(s.id)
                        aggregate = geladeneAggregate
                        kennwerte = AkustischeKennwerte.ausAggregaten(geladeneAggregate)
                    }
                }
            }
            launch {
                db.connectionEventDao().fuerSessionFlow(s.id).collectLatest { events ->
                    ausfallbaender = leiteAusfallbaenderAb(events, s.endedAt)
                }
            }
        } else {
            messwerte = emptyList()
            aggregate = emptyList()
            kennwerte = null
            ausfallbaender = emptyList()
        }
    }

    LaunchedEffect(dienstAktiv, letzteSession?.endedAt) {
        while (dienstAktiv || (letzteSession != null && letzteSession?.endedAt == null)) {
            jetzt = System.currentTimeMillis()
            delay(1000)
        }
    }

    val liveLevel = letzterFrame?.level
    val isCalibrated = verbindungszustand == ConnectionState.STREAMING && liveLevel != null
    val weightingText = letzterFrame?.weighting?.let { "dB(${it.name})" } ?: if (isCalibrated) "dB(A)" else "dB"

    // Laufzeituhr für aktive Messung
    val sessionStartTime = letzteSession?.startedAt
    val elapsedSeconds = if (dienstAktiv && sessionStartTime != null) ((jetzt - sessionStartTime) / 1000).coerceAtLeast(0) else 0L
    val timerString = String.format(Locale.US, "%02d:%02d:%02d", elapsedSeconds / 3600, (elapsedSeconds % 3600) / 60, elapsedSeconds % 60)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ==========================================
        // 1. TOP HEADER & STATUS PILL
        // ==========================================
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Noise Protocol",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (dienstAktiv) "Live Monitoring" else "System Ready",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Top-Right Status Chip
            val isConnected = verbindungszustand == ConnectionState.STREAMING
            val chipBg = if (isConnected) Color(0xFFDCFCE7) else MaterialTheme.colorScheme.surfaceVariant
            val chipContentColor = if (isConnected) Color(0xFF15803D) else MaterialTheme.colorScheme.onSurfaceVariant
            val chipText = if (isConnected) "PCE-323 Connected" else if (dienstAktiv) "Mic Active" else "PCE-323 Ready"

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(chipBg)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isConnected) Color(0xFF22C55E) else Color(0xFF94A3B8))
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = chipText,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = chipContentColor
                )
            }
        }

        if (!dienstAktiv) {
            // ==========================================
            // IDLE SCREEN: "Ready to measure" (Image 2)
            // ==========================================
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Zentrales rundes Mikrofon/Sensor-Badge
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = AppIcons.Mic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(44.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Ready to measure",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isCalibrated) "Device is calibrated and synchronized" else "Sensors ready for noise monitoring",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Großer CTA: "Start measurement"
                Button(
                    onClick = {
                        if (hasAudioPermission) {
                            val intent = Intent(context, AudioRecordingService::class.java).apply {
                                putExtra(EXTRA_START_AUDIO_MONITORING, true)
                            }
                            context.startForegroundService(intent)
                        } else {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.RECORD_AUDIO,
                                    Manifest.permission.POST_NOTIFICATIONS
                                )
                            )
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag(START_MEASUREMENT_BUTTON_TAG)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Start measurement",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Measurement Settings Header & Quick Rows
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Measurement Settings",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                OutlinedButton(
                    onClick = { onNavigateToSettings?.invoke() },
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("Advanced", style = MaterialTheme.typography.labelMedium)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Card 1: Automatic Event Detection
                SettingQuickRow(
                    icon = AppIcons.Sparkle,
                    title = "Automatic Event Detection",
                    subtitle = "AI-powered noise classification",
                    trailing = {
                        Switch(
                            checked = autoEventDetection,
                            onCheckedChange = {
                                autoEventDetection = it
                                settings.aiEnabled = it
                            }
                        )
                    }
                )

                // Card 2: Threshold Level
                SettingQuickRow(
                    icon = AppIcons.Speedometer,
                    title = "Threshold Level",
                    subtitle = "${settings.dbThreshold.toInt()}.0 dB(A) · ${when (settings.audioTriggerQuelle) { "PCE_323" -> "Nur PCE-323"; "MIKROFON" -> "Nur Mikrofon"; else -> "Auto" }}",
                    onClick = { onNavigateToSettings?.invoke() },
                    trailing = {
                        Icon(
                            imageVector = AppIcons.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )

                // Card 3: Embedded Audio Snippet
                SettingQuickRow(
                    icon = AppIcons.Mic,
                    title = "Embedded Audio Snippet",
                    subtitle = "Record 10s audio for each event",
                    trailing = {
                        Switch(
                            checked = audioSnippetEnabled,
                            onCheckedChange = {
                                audioSnippetEnabled = it
                                settings.driveUploadWav = it
                            }
                        )
                    }
                )

                // Card 4: Quiet Hours
                SettingQuickRow(
                    icon = AppIcons.Bed,
                    title = "Quiet Hours",
                    subtitle = "${settings.quietHoursStartHour}:00 – ${settings.quietHoursEndHour}:00",
                    onClick = { onNavigateToSettings?.invoke() },
                    trailing = {
                        Icon(
                            imageVector = AppIcons.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            }

            // System Health Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF22C55E),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (verbindungszustand == ConnectionState.STREAMING) "Bluetooth Signal: Excellent" else "Bluetooth Device: Ready",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF22C55E),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Internal Mic: Active",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    TextButton(
                        onClick = { onNavigateToDiagnose?.invoke() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "View System Diagnostics",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        } else {
            // ==========================================
            // LIVE ACTIVE SCREEN: "Measurement Running" (Image 1)
            // ==========================================
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "MEASUREMENT RUNNING",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))

                // Riesige dB-Zahl
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = if (liveLevel != null) String.format(Locale.US, "%.1f", liveLevel) else "--.-",
                        style = MaterialTheme.typography.displayLarge.copy(fontSize = 64.sp),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = weightingText,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }

                val levelVal = liveLevel ?: 0.0
                val levelDescription = when {
                    levelVal <= 0.0 -> "Waiting for level updates..."
                    levelVal < 45.0 -> "Background noise level is low"
                    levelVal < 65.0 -> "Moderate ambient noise level"
                    else -> "High noise level exceeding standard threshold"
                }

                Text(
                    text = levelDescription,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Sound Level History Card
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Sound Level History",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = timerString,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    val s = letzteSession
                    if (s != null && (messwerte.isNotEmpty() || aggregate.isNotEmpty())) {
                        val sessionEndeFuerChart = s.endedAt ?: jetzt
                        val chartSpalten = remember(messwerte, aggregate, s.startedAt, sessionEndeFuerChart) {
                            if (messwerte.isNotEmpty()) {
                                downsampleMesswerteFuerChart(messwerte, s.startedAt, sessionEndeFuerChart)
                            } else {
                                downsampleAggregateFuerChart(aggregate, s.startedAt, sessionEndeFuerChart)
                            }
                        }

                        PegelverlaufChart(
                            spalten = chartSpalten,
                            ausfallbaender = ausfallbaender,
                            sessionStart = s.startedAt,
                            sessionEnde = sessionEndeFuerChart,
                            thresholdDb = settings.dbThreshold.toDouble(),
                            laeqDb = kennwerte?.leqDb,
                            isLive = true,
                            height = 200.dp
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Erfasse Live-Messdaten...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Stat Cards (LAeq & LMax)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard(
                    title = "LAeq (Avg)",
                    value = kennwerte?.leqDb?.let { String.format(Locale.US, "%.1f", it) } ?: "--.-",
                    unit = "dB",
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = "LMax (Peak)",
                    value = kennwerte?.maxDb?.let { String.format(Locale.US, "%.1f", it) } ?: "--.-",
                    unit = "dB",
                    modifier = Modifier.weight(1f)
                )
            }

            // Action Buttons
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { showMarkNoiseEventSheet = true },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag(MARK_NOISE_EVENT_BUTTON_TAG)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "+ Mark noise event",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick = {
                        val intent = Intent(context, AudioRecordingService::class.java).apply {
                            action = ACTION_STOP_SERVICE
                        }
                        context.startService(intent)
                        container.connectionSupervisor.stop()
                    },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag(END_MEASUREMENT_BUTTON_TAG)
                ) {
                    Text(
                        text = "End measurement",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Connected Meter Info Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                    .clickable { onNavigateToMeter?.invoke() }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = AppIcons.Sensors,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = settings.meterDeviceName ?: "PCE-323 Digital Sound Meter",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (isCalibrated) "Calibrated · Bluetooth LE Active" else "Smartphone Sensor Active",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Icon(
                        imageVector = AppIcons.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    // Modal Bottom Sheet für Mark Noise Event
    if (showMarkNoiseEventSheet) {
        MarkNoiseEventBottomSheet(
            currentDb = liveLevel,
            currentWeighting = weightingText,
            onDismiss = { showMarkNoiseEventSheet = false },
            onSaveEvent = { category, note ->
                scope.launch {
                    val now = System.currentTimeMillis()
                    val record = NoiseRecord(
                        timestamp = now,
                        amplitude = 0.0,
                        dbValue = liveLevel ?: 0.0,
                        filePath = "",
                        label = category,
                        calibratedDbA = if (isCalibrated) liveLevel else null,
                        meterConnected = isCalibrated,
                        notes = if (note.isNotBlank()) note else null
                    )
                    db.noiseDao().insert(record)
                    onShowSnackbar("Ereignis '$category' gespeichert")
                }
            }
        )
    }
}

@Composable
private fun SettingQuickRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            trailing()
        }
    }
}

@Composable
private fun StatCard(
    title: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = unit,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp)
                )
            }
        }
    }
}
