package com.example.lrmprotokoll.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.lrmprotokoll.audio.ACTION_START_AUDIO_MONITORING
import com.example.lrmprotokoll.audio.ACTION_STOP_AUDIO_RECORDING
import com.example.lrmprotokoll.audio.AudioRecordingService

const val WAV_STOP_CONFIRM_DIALOG_TAG = "wav_stop_confirm_dialog"

/**
 * Status-Badge fuer die TopAppBar. Entscheidend ist der echte Laufzeitzustand des AudioRecord-
 * Pfads, nicht nur "Foreground-Service laeuft". Genau dadurch wird ein Zustand sichtbar, in dem
 * das PCE weiter misst, die WAV-/Mikrofonkette aber ausgefallen ist.
 *
 * [audioMonitoringActive] bleibt aus API-Kompatibilitaetsgruenden im Parameter-Set; die Anzeige
 * verwendet absichtlich [AudioRecordingService.audioAufnahmeAktiv] als Source of Truth.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun MicrophoneStatusBadge(
    audioMonitoringActive: Boolean,
    recordWavAudio: Boolean = true,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val runtimeAudioActive by AudioRecordingService.audioAufnahmeAktiv.collectAsState()
    var showStopConfirm by remember { mutableStateOf(false) }

    fun starteAudio() {
        val intent = Intent(context, AudioRecordingService::class.java).apply {
            action = ACTION_START_AUDIO_MONITORING
        }
        ContextCompat.startForegroundService(context, intent)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.RECORD_AUDIO] == true) starteAudio()
    }

    fun starteAudioMitBerechtigung() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            starteAudio()
        } else {
            val permissions = buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    val (statusColor, containerColor, text) = when {
        !recordWavAudio -> Triple(
            Color(0xFF475569),
            Color(0xFFF1F5F9),
            "WAV: AUS (DSGVO)",
        )
        runtimeAudioActive -> Triple(
            Color(0xFF15803D),
            Color(0xFFDCFCE7),
            "WAV: AKTIV",
        )
        else -> Triple(
            Color(0xFFB45309),
            Color(0xFFFFF7ED),
            "WAV: INAKTIV",
        )
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        modifier = modifier.clickable {
            if (runtimeAudioActive) showStopConfirm = true else starteAudioMitBerechtigung()
        }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }

    if (showStopConfirm) {
        AlertDialog(
            modifier = Modifier.testTag(WAV_STOP_CONFIRM_DIALOG_TAG),
            onDismissRequest = { showStopConfirm = false },
            title = { Text("WAV-Aufzeichnung beenden?") },
            text = {
                Text(
                    "Die Mikrofon-/WAV-Erfassung wird beendet. Das PCE-323 kann weiter messen, " +
                        "aber neue Lärmereignisse erhalten dann keinen Tonmitschnitt, bis WAV wieder gestartet wird."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showStopConfirm = false
                        context.startService(Intent(context, AudioRecordingService::class.java).apply {
                            action = ACTION_STOP_AUDIO_RECORDING
                        })
                    }
                ) { Text("WAV beenden") }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirm = false }) { Text("Abbrechen") }
            },
        )
    }
}
