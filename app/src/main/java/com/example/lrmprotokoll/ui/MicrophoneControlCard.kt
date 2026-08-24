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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.audio.ACTION_START_AUDIO_MONITORING
import com.example.lrmprotokoll.audio.ACTION_STOP_AUDIO_RECORDING
import com.example.lrmprotokoll.audio.AudioRecordingService
import com.example.lrmprotokoll.audio.EXTRA_START_AUDIO_MONITORING
import com.example.lrmprotokoll.ui.components.NoiseCard
import com.example.lrmprotokoll.ui.components.StatusPill
import com.example.lrmprotokoll.ui.components.StatusPillType
import java.util.Locale

/**
 * 1. Sektion des Startscreens: Smartphone-Mikrofon-Aufnahme & Live-dB Anzeige.
 *
 * Zeigt eindeutig an, ob das Mikrofon aktiv läuft (Überwachung & Pre-Roll),
 * bietet Start-/Stopp-Steuerung, Schwellenwert-Regler und die Auswahl der Trigger-Quelle.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MicrophoneControlCard(
    modifier: Modifier = Modifier,
    onShowSnackbar: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as LaermprotokollApp).container }
    val settings = container.settingsManager

    val audioAufnahmeAktiv by AudioRecordingService.audioAufnahmeAktiv.collectAsState()
    val dienstAktiv by AudioRecordingService.laeuft.collectAsState()

    var dbThreshold by remember { mutableFloatStateOf(settings.dbThreshold) }
    var triggerQuelle by remember { mutableStateOf(settings.audioTriggerQuelle) }

    val hasAudioPermission = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.RECORD_AUDIO] == true) {
            val intent = Intent(context, AudioRecordingService::class.java).apply {
                action = ACTION_START_AUDIO_MONITORING
                putExtra(EXTRA_START_AUDIO_MONITORING, true)
            }
            context.startForegroundService(intent)
            onShowSnackbar("Mikrofon-Aufnahme gestartet")
        } else {
            Toast.makeText(context, "Mikrofon-Berechtigung erforderlich", Toast.LENGTH_SHORT).show()
        }
    }

    NoiseCard(
        modifier = modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        // Kopfzeile: Titel, Status & Live-Indikator
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Phone,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "1. Smartphone-Mikrofon",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (audioAufnahmeAktiv) "Pre-Roll Puffer & Schwellenüberwachung aktiv" else "Mikrofon-Überwachung pausiert",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            StatusPill(
                text = if (!settings.recordWavAudio) "Audio AUS (DSGVO)" else if (audioAufnahmeAktiv) "Mikrofon AKTIV" else "Mikrofon AUS",
                icon = if (settings.recordWavAudio && audioAufnahmeAktiv) Icons.Default.Check else null,
                type = if (!settings.recordWavAudio) StatusPillType.IDLE else if (audioAufnahmeAktiv) StatusPillType.CONNECTED else StatusPillType.IDLE
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Schwellenwert-Einstellung für Audioaufzeichnung
        Text(
            text = "Aufnahme-Schwelle: ${String.format(Locale.getDefault(), "%.1f", dbThreshold)} dB",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
        Slider(
            value = dbThreshold,
            onValueChange = { dbThreshold = it },
            onValueChangeFinished = { settings.dbThreshold = dbThreshold },
            valueRange = 30f..100f,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Trigger-Quelle Auswahl
        Text(
            text = "Auslösequelle für Audio-WAV:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            FilterChip(
                selected = triggerQuelle == "AUTO",
                onClick = {
                    triggerQuelle = "AUTO"
                    settings.audioTriggerQuelle = "AUTO"
                },
                label = { Text("Automatisch") }
            )
            FilterChip(
                selected = triggerQuelle == "PCE_323",
                onClick = {
                    triggerQuelle = "PCE_323"
                    settings.audioTriggerQuelle = "PCE_323"
                },
                label = { Text("Nur PCE-323") }
            )
            FilterChip(
                selected = triggerQuelle == "MIKROFON",
                onClick = {
                    triggerQuelle = "MIKROFON"
                    settings.audioTriggerQuelle = "MIKROFON"
                },
                label = { Text("Nur Mikrofon") }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Start/Stopp-Button für Mikrofon-Aufnahme
        if (!audioAufnahmeAktiv) {
            Button(
                onClick = {
                    if (hasAudioPermission) {
                        val intent = Intent(context, AudioRecordingService::class.java).apply {
                            action = ACTION_START_AUDIO_MONITORING
                            putExtra(EXTRA_START_AUDIO_MONITORING, true)
                        }
                        context.startForegroundService(intent)
                        onShowSnackbar("Mikrofon-Aufnahme gestartet")
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
                    .height(44.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Mikrofon-Aufnahme starten", fontWeight = FontWeight.Bold)
            }
        } else {
            OutlinedButton(
                onClick = {
                    val intent = Intent(context, AudioRecordingService::class.java).apply {
                        action = ACTION_STOP_AUDIO_RECORDING
                    }
                    context.startService(intent)
                    onShowSnackbar("Mikrofon-Aufnahme angehalten")
                },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Mikrofon-Aufnahme stoppen")
            }
        }
    }
}
