package com.example.lrmprotokoll.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import com.example.lrmprotokoll.ui.components.NoiseCard
import com.example.lrmprotokoll.ui.components.StatusPill
import com.example.lrmprotokoll.ui.components.StatusPillType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 1-Sekunden Live-Cockpit & Hauptsteuerungs-Karte für den Startbildschirm (UX-Briefing Punkte 4, 7, 8, 10, 13).
 */
@Composable
fun LiveCockpitCard(
    modifier: Modifier = Modifier,
    onShowSnackbar: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as LaermprotokollApp).container }
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

    var showQuickTagDialog by remember { mutableStateOf(false) }

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
            delay(10_000)
        }
    }

    val liveLevel = letzterFrame?.level
    val isCalibrated = verbindungszustand == ConnectionState.STREAMING && liveLevel != null
    val weightingText = letzterFrame?.weighting?.let { "dB(${it.name})" } ?: if (isCalibrated) "dB(A)" else "dB"

    NoiseCard(
        modifier = modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        // 1. 1-SEKUNDEN HEADER (Pegel, Leq, Quelle, Status)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = if (liveLevel != null) "%.1f".format(Locale.US, liveLevel) else if (dienstAktiv) "--" else "0.0",
                        style = MaterialTheme.typography.displaySmall.copy(fontSize = 34.sp),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = weightingText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }

                kennwerte?.let { kw ->
                    val leqStr = kw.leqDb?.let { "Ø Leq: %.1f dB".format(Locale.US, it) }
                    val maxStr = kw.maxDb?.let { "Max: %.1f dB".format(Locale.US, it) }
                    val info = listOfNotNull(leqStr, maxStr).joinToString(" · ")
                    if (info.isNotBlank()) {
                        Text(
                            text = info,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (isCalibrated) {
                    val pillLabel = letzterFrame?.weighting?.let { "PCE-323 (${it.name})" } ?: "PCE-323 Kalibriert"
                    StatusPill(
                        text = pillLabel,
                        icon = Icons.Default.Check,
                        type = StatusPillType.CALIBRATED
                    )
                } else if (dienstAktiv) {
                    StatusPill(
                        text = "Smartphone Mikrofon",
                        icon = Icons.Default.Phone,
                        type = StatusPillType.NEUTRAL
                    )
                } else {
                    StatusPill(
                        text = "Bereit",
                        type = StatusPillType.IDLE
                    )
                }

                if (dienstAktiv) {
                    val statusText = when (verbindungszustand) {
                        ConnectionState.STREAMING -> "Messung läuft"
                        ConnectionState.RECONNECTING, ConnectionState.DEGRADED -> "Verbinde erneut…"
                        ConnectionState.CONNECTING, ConnectionState.SCANNING, ConnectionState.DISCOVERING, ConnectionState.SUBSCRIBING -> "Verbinde…"
                        else -> "Aufnahme aktiv"
                    }
                    val statusType = when (verbindungszustand) {
                        ConnectionState.STREAMING -> StatusPillType.CONNECTED
                        ConnectionState.RECONNECTING, ConnectionState.DEGRADED -> StatusPillType.WARNING
                        else -> StatusPillType.ACCENT
                    }
                    StatusPill(text = statusText, type = statusType)
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 2. LIVE-PEGELVERLAUF KURVE (Verlauf steht im Fokus, vergrößert nach Start)
        val s = letzteSession
        if (s != null && (messwerte.isNotEmpty() || aggregate.isNotEmpty())) {
            val isLive = s.endedAt == null
            val sessionEndeFuerChart = s.endedAt ?: jetzt
            val chartSpalten = remember(messwerte, aggregate, s.startedAt, sessionEndeFuerChart) {
                if (messwerte.isNotEmpty()) {
                    downsampleMesswerteFuerChart(messwerte, s.startedAt, sessionEndeFuerChart)
                } else {
                    downsampleAggregateFuerChart(aggregate, s.startedAt, sessionEndeFuerChart)
                }
            }

            val threshold = container.settingsManager.dbThreshold.toDouble()

            PegelverlaufChart(
                spalten = chartSpalten,
                ausfallbaender = ausfallbaender,
                sessionStart = s.startedAt,
                sessionEnde = sessionEndeFuerChart,
                thresholdDb = threshold,
                laeqDb = kennwerte?.leqDb,
                isLive = isLive,
                height = if (dienstAktiv) 220.dp else 160.dp
            )
        } else {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = if (dienstAktiv) "Erfasse Live-Messdaten…" else "Noch keine aktive Messung gestartet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 3. HAUPTAKTIIONSBUTTONS (Groß & Eindeutig)
        if (!dienstAktiv) {
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
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Messung starten",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Großer "Lärmereignis markieren"-Button (nur im Messmodus)
                Button(
                    onClick = { showQuickTagDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Lärmereignis markieren",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Messung beenden")
                }
            }
        }
    }

    if (showQuickTagDialog) {
        QuickEventTagDialog(
            currentDb = liveLevel,
            onDismiss = { showQuickTagDialog = false },
            onSave = { category, note ->
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
                    showQuickTagDialog = false
                    onShowSnackbar("Ereignis '$category' protokolliert")
                }
            }
        )
    }
}
