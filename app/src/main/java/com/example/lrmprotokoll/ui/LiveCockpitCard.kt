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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.audio.ACTION_STOP_SERVICE
import com.example.lrmprotokoll.audio.AudioRecordingService
import com.example.lrmprotokoll.audio.EXTRA_START_AUDIO_MONITORING
import com.example.lrmprotokoll.data.MeasurementEntity
import com.example.lrmprotokoll.data.MinuteAggregateEntity
import com.example.lrmprotokoll.data.NoiseRecord
import com.example.lrmprotokoll.data.SessionEntity
import com.example.lrmprotokoll.meter.ConnectionState
import com.example.lrmprotokoll.messreihe.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

const val START_MEASUREMENT_BUTTON_TAG = "start_measurement_button"
const val END_MEASUREMENT_BUTTON_TAG = "end_measurement_button"
const val MARK_NOISE_EVENT_BUTTON_TAG = "mark_noise_event_button"
const val VIDEO_BEWEIS_BUTTON_TAG = "video_beweis_button"
const val END_MEASUREMENT_CONFIRM_DIALOG_TAG = "end_measurement_confirm_dialog"
const val DISABLE_WAV_CONFIRM_DIALOG_TAG = "disable_wav_confirm_dialog"
const val DISCONNECT_BLUETOOTH_CONFIRM_DIALOG_TAG = "disconnect_bluetooth_confirm_dialog"

/** Zeitfenster des Live-Charts (dieselbe Grenze wie in der Chart-Anzeige weiter unten) und die
 * Rasterung fuer [berechneDbFensterAb] - beide an einer Stelle, damit sie nicht auseinanderlaufen. */
private const val LIVE_FENSTER_MS = 4 * 3600 * 1000L
private const val LIVE_FENSTER_RASTER_MS = 5 * 60 * 1000L

/**
 * Untere Zeitgrenze fuer die DB-Abfrage des Live-Cockpits (PROMPT_M9A.md Aufgabe 1): dieselbe
 * Fensterlogik wie die Chart-Anzeige (4 Stunden, nur waehrend die Ueberwachung noch laeuft -
 * eine bereits beendete Session zeigt weiterhin ihre volle Laenge), aber auf [rasterMs]
 * abgerundet. Ohne die Rasterung wuerde die Grenze bei jedem Sekundentick einen neuen Wert
 * liefern und den Flow, der darauf lauscht, jede Sekunde neu abonnieren statt nur alle
 * [rasterMs] - das waere schlimmer als der Ausgangszustand, nicht besser.
 *
 * Das Ergebnis liegt immer auf oder vor der exakten Fenstergrenze, nie danach - die Anzeige
 * filtert selbst noch einmal exakt (siehe `chartStart` unten), die DB-Abfrage darf also
 * hoechstens etwas MEHR laden als angezeigt wird, nie weniger.
 */
internal fun berechneDbFensterAb(
    sessionStart: Long,
    sessionEndeOderJetzt: Long,
    dienstAktiv: Boolean,
    fensterMs: Long = LIVE_FENSTER_MS,
    rasterMs: Long = LIVE_FENSTER_RASTER_MS,
): Long {
    if (!dienstAktiv || sessionEndeOderJetzt - sessionStart <= fensterMs) return sessionStart
    val versatz = sessionEndeOderJetzt - fensterMs - sessionStart
    return sessionStart + (versatz / rasterMs) * rasterMs
}

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
    /** M11 Etappe B: Videobeweis - nur bei laufender Aufzeichnung sichtbar. */
    onNavigateToVideo: (() -> Unit)? = null,
    onShowSnackbar: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as LaermprotokollApp).container }
    val settings = container.settingsManager
    val scope = rememberCoroutineScope()

    val dienstAktiv by AudioRecordingService.laeuft.collectAsState()
    val verbindungszustand by container.connectionSupervisor.state.collectAsState()
    val letzterFrame by container.meterTransport.frames.collectAsState(initial = null)
    val micDb by AudioRecordingService.currentMicDb.collectAsState()

    val db = container.database
    val letzteSession by db.sessionDao().letzteSessionFlow().collectAsState(initial = null)
    var messwerte by remember { mutableStateOf<List<MeasurementEntity>>(emptyList()) }
    var aggregate by remember { mutableStateOf<List<MinuteAggregateEntity>>(emptyList()) }
    var kennwerte by remember { mutableStateOf<AkustischeKennwerte.Kennwerte?>(null) }
    var ausfallbaender by remember { mutableStateOf<List<Ausfallband>>(emptyList()) }
    var jetzt by remember { mutableLongStateOf(System.currentTimeMillis()) }

    var showMarkNoiseEventSheet by remember { mutableStateOf(false) }
    var showEndMeasurementConfirm by remember { mutableStateOf(false) }
    var showDisableWavConfirm by remember { mutableStateOf(false) }
    var showDisconnectBluetoothConfirm by remember { mutableStateOf(false) }

    // Notification-Aktion "Stoppen" kann selbst keinen Dialog zeigen (siehe PendingUiAction) -
    // sie signalisiert hierueber, dass derselbe Bestaetigungsdialog wie beim In-App-Button
    // erscheinen soll, statt die Messung ungefragt zu beenden.
    val stopConfirmationRequested by PendingUiAction.stopConfirmationRequested.collectAsState()
    LaunchedEffect(stopConfirmationRequested) {
        if (stopConfirmationRequested) {
            showEndMeasurementConfirm = true
            PendingUiAction.consumeStopConfirmationRequest()
        }
    }

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

    val dbFensterAb = letzteSession?.let { s ->
        berechneDbFensterAb(s.startedAt, s.endedAt ?: jetzt, dienstAktiv)
    }

    LaunchedEffect(letzteSession?.id, dbFensterAb) {
        val s = letzteSession
        if (s != null && dbFensterAb != null) {
            launch {
                db.measurementDao().fuerSessionAbFlow(s.id, dbFensterAb).collectLatest { geladeneMesswerte ->
                    messwerte = geladeneMesswerte
                    if (geladeneMesswerte.isNotEmpty()) {
                        kennwerte = withContext(Dispatchers.Default) {
                            AkustischeKennwerte.leqUndMax(geladeneMesswerte)
                        }
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

    val istMikrofonMessung = letzteSession?.deviceAddress?.isBlank() == true
    val isCalibrated = dienstAktiv && verbindungszustand == ConnectionState.STREAMING && letzterFrame != null
    val liveLevel = if (isCalibrated) letzterFrame?.level else if (dienstAktiv) micDb else null
    val weightingText = if (isCalibrated) letzterFrame?.weighting?.let { "dB(${it.name})" } ?: "dB" else "dB"

    val sessionStartTime = letzteSession?.startedAt
    val elapsedSeconds = if (dienstAktiv && sessionStartTime != null) ((jetzt - sessionStartTime) / 1000).coerceAtLeast(0) else 0L
    val timerString = String.format(Locale.US, "%02d:%02d:%02d", elapsedSeconds / 3600, (elapsedSeconds % 3600) / 60, elapsedSeconds % 60)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        val istRuhe = istAktuellRuhezeit(settings)
        val schwelle = if (istRuhe) settings.quietHoursThreshold else settings.dbThreshold
        var showTriggerMenu by remember { mutableStateOf(false) }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    text = stringResource(R.string.cockpit_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    text = stringResource(if (dienstAktiv) R.string.cockpit_subtitle_live else R.string.cockpit_subtitle_ready),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.clickable { showTriggerMenu = true }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = if (!settings.recordWavAudio) {
                                    "Kein Audio (DSGVO)"
                                } else when (settings.audioTriggerQuelle) {
                                    "PCE_323" -> stringResource(R.string.cockpit_trigger_meter_only)
                                    "MIKROFON" -> stringResource(R.string.cockpit_trigger_mic_only)
                                    else -> {
                                        val aktiveQuelle = if (verbindungszustand == ConnectionState.STREAMING) {
                                            stringResource(R.string.cockpit_trigger_active_meter)
                                        } else {
                                            stringResource(R.string.cockpit_trigger_active_mic)
                                        }
                                        "${stringResource(R.string.cockpit_trigger_auto)} → $aktiveQuelle"
                                    }
                                },
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1
                            )
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = showTriggerMenu,
                        onDismissRequest = { showTriggerMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.settings_trigger_source_auto)) },
                            onClick = {
                                settings.audioTriggerQuelle = "AUTO"
                                showTriggerMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.settings_trigger_source_meter)) },
                            onClick = {
                                settings.audioTriggerQuelle = "PCE_323"
                                showTriggerMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.settings_trigger_source_mic)) },
                            onClick = {
                                showTriggerMenu = false
                                if (verbindungszustand == ConnectionState.IDLE ||
                                    verbindungszustand == ConnectionState.DISCONNECTED ||
                                    verbindungszustand == ConnectionState.FAILED
                                ) {
                                    settings.audioTriggerQuelle = "MIKROFON"
                                } else {
                                    showDisconnectBluetoothConfirm = true
                                }
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(if (settings.recordWavAudio) "WAV-Aufnahme: Aktiv" else "WAV-Aufnahme: Aus (DSGVO)") },
                            onClick = {
                                showTriggerMenu = false
                                if (settings.recordWavAudio) {
                                    showDisableWavConfirm = true
                                } else {
                                    settings.recordWavAudio = true
                                }
                            }
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.cockpit_threshold_display, schwelle),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.clickable { onNavigateToSettings?.invoke() },
                    maxLines = 1
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(if (dienstAktiv) R.string.cockpit_measuring_running else R.string.cockpit_ready_to_measure),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = if (liveLevel != null) String.format(Locale.US, "%.1f", liveLevel) else "--.-",
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = weightingText,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp),
                    maxLines = 1
                )
            }

            val levelVal = liveLevel ?: 0.0
            val levelDescription = when {
                !dienstAktiv -> stringResource(R.string.cockpit_level_desc_ready)
                levelVal <= 0.0 -> stringResource(R.string.cockpit_level_desc_waiting)
                levelVal < 45.0 -> stringResource(R.string.cockpit_level_desc_low)
                levelVal < 65.0 -> stringResource(R.string.cockpit_level_desc_moderate)
                else -> stringResource(R.string.cockpit_level_desc_high)
            }

            Text(
                text = levelDescription,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

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
                        text = if (istMikrofonMessung) {
                            stringResource(R.string.cockpit_history_title_mic)
                        } else {
                            stringResource(R.string.cockpit_history_title)
                        },
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

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (istMikrofonMessung) {
                                    "${stringResource(R.string.cockpit_leq_label_mic)}: "
                                } else {
                                    "${stringResource(R.string.cockpit_laeq_label)}: "
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = kennwerte?.leqDb?.let { "${String.format(Locale.US, "%.1f", it)} dB" } ?: "--.- dB",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (istMikrofonMessung) {
                                    "${stringResource(R.string.cockpit_lmax_label_mic)}: "
                                } else {
                                    "${stringResource(R.string.cockpit_lmax_label)}: "
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = kennwerte?.maxDb?.let { "${String.format(Locale.US, "%.1f", it)} dB" } ?: "--.- dB",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                val s = letzteSession
                if (s != null && (messwerte.isNotEmpty() || aggregate.isNotEmpty())) {
                    val sessionEndeFuerChart = if (dienstAktiv) jetzt else (s.endedAt ?: jetzt)
                    val chartStart = if (dienstAktiv && (sessionEndeFuerChart - s.startedAt) > LIVE_FENSTER_MS) {
                        sessionEndeFuerChart - LIVE_FENSTER_MS
                    } else {
                        s.startedAt
                    }

                    val chartSpalten = remember(messwerte, aggregate, chartStart, sessionEndeFuerChart) {
                        val filteredMesswerte = if (chartStart > s.startedAt) messwerte.filter { it.timestamp in chartStart..sessionEndeFuerChart } else messwerte
                        val filteredAggregate = if (chartStart > s.startedAt) aggregate.filter { it.minuteStart in chartStart..sessionEndeFuerChart } else aggregate

                        if (filteredMesswerte.isNotEmpty()) {
                            downsampleMesswerteFuerChart(filteredMesswerte, chartStart, sessionEndeFuerChart)
                        } else if (filteredAggregate.isNotEmpty()) {
                            downsampleAggregateFuerChart(filteredAggregate, chartStart, sessionEndeFuerChart)
                        } else {
                            emptyList()
                        }
                    }

                    if (istMikrofonMessung) {
                        Text(
                            text = stringResource(R.string.cockpit_mic_uncalibrated),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }

                    PegelverlaufChart(
                        spalten = chartSpalten,
                        ausfallbaender = ausfallbaender,
                        sessionStart = chartStart,
                        sessionEnde = sessionEndeFuerChart,
                        thresholdDb = schwelle.toDouble(),
                        laeqDb = kennwerte?.leqDb,
                        isLive = dienstAktiv,
                        height = 260.dp
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
                            text = if (dienstAktiv) stringResource(R.string.cockpit_collecting_live_data) else stringResource(R.string.cockpit_start_to_see_history),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (dienstAktiv) {
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
                        text = stringResource(R.string.cockpit_mark_noise_event),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (onNavigateToVideo != null) {
                    OutlinedButton(
                        onClick = onNavigateToVideo,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag(VIDEO_BEWEIS_BUTTON_TAG)
                    ) {
                        Text(
                            text = stringResource(R.string.cockpit_video_beweis),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                OutlinedButton(
                    onClick = { showEndMeasurementConfirm = true },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag(END_MEASUREMENT_BUTTON_TAG)
                ) {
                    Text(
                        text = stringResource(R.string.cockpit_end_measurement),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        } else {
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
                    text = stringResource(R.string.cockpit_start_measurement),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    if (showEndMeasurementConfirm) {
        AlertDialog(
            modifier = Modifier.testTag(END_MEASUREMENT_CONFIRM_DIALOG_TAG),
            onDismissRequest = { showEndMeasurementConfirm = false },
            title = { Text("Messung wirklich beenden?") },
            text = {
                Text(
                    "Damit werden die laufende Messung, die WAV-/Mikrofonaufzeichnung und die " +
                        "PCE-323-Verbindung beendet. Die bisher erfassten Daten bleiben erhalten."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEndMeasurementConfirm = false
                        context.startService(Intent(context, AudioRecordingService::class.java).apply {
                            action = ACTION_STOP_SERVICE
                        })
                        container.connectionSupervisor.stop()
                    }
                ) { Text("Messung beenden") }
            },
            dismissButton = {
                TextButton(onClick = { showEndMeasurementConfirm = false }) { Text("Abbrechen") }
            },
        )
    }

    if (showDisableWavConfirm) {
        AlertDialog(
            modifier = Modifier.testTag(DISABLE_WAV_CONFIRM_DIALOG_TAG),
            onDismissRequest = { showDisableWavConfirm = false },
            title = { Text("WAV-Aufzeichnung deaktivieren?") },
            text = {
                Text(
                    "Neue Lärmereignisse werden dann ohne WAV-Beweisdatei gespeichert. " +
                        "Pegelprotokoll und PCE-323-Messung können weiterlaufen."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        settings.recordWavAudio = false
                        showDisableWavConfirm = false
                    }
                ) { Text("WAV deaktivieren") }
            },
            dismissButton = {
                TextButton(onClick = { showDisableWavConfirm = false }) { Text("Abbrechen") }
            },
        )
    }

    if (showDisconnectBluetoothConfirm) {
        AlertDialog(
            modifier = Modifier.testTag(DISCONNECT_BLUETOOTH_CONFIRM_DIALOG_TAG),
            onDismissRequest = { showDisconnectBluetoothConfirm = false },
            title = { Text("Bluetooth-Verbindung beenden?") },
            text = {
                Text(
                    "Der Trigger wird auf das interne Mikrofon umgestellt und die Verbindung zum " +
                        "PCE-323 beendet. Kalibrierte PCE-Messwerte stehen danach nicht mehr zur Verfügung."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        settings.audioTriggerQuelle = "MIKROFON"
                        container.connectionSupervisor.stop()
                        showDisconnectBluetoothConfirm = false
                    }
                ) { Text("PCE trennen") }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectBluetoothConfirm = false }) { Text("Abbrechen") }
            },
        )
    }

    if (showMarkNoiseEventSheet) {
        MarkNoiseEventBottomSheet(
            currentDb = liveLevel,
            currentWeighting = if (isCalibrated) weightingText else "dB (Mikrofon, unkalibriert)",
            onDismiss = { showMarkNoiseEventSheet = false },
            onSaveEvent = { category, note ->
                val now = System.currentTimeMillis()
                val microphoneLevel = AudioRecordingService.currentMicDb.value
                val meterLevel = if (isCalibrated) letzterFrame?.level else null
                scope.launch {
                    val record = NoiseRecord(
                        timestamp = now,
                        amplitude = 0.0,
                        dbValue = microphoneLevel ?: 0.0,
                        filePath = "",
                        label = category,
                        calibratedDbA = meterLevel,
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

fun istAktuellRuhezeit(settings: com.example.lrmprotokoll.data.SettingsManager): Boolean {
    if (!settings.quietHoursEnabled) return false
    val cal = java.util.Calendar.getInstance()
    val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
    val start = settings.quietHoursStartHour
    val end = settings.quietHoursEndHour
    return if (start <= end) (hour >= start && hour < end)
    else (hour >= start || hour < end)
}
