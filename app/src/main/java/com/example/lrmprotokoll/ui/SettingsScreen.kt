package com.example.lrmprotokoll.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.lrmprotokoll.LaermprotokollApp
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { (context.applicationContext as LaermprotokollApp).container.settingsManager }
    
    var dbThreshold by remember { mutableFloatStateOf(settings.dbThreshold) }
    var preRoll by remember { mutableFloatStateOf(settings.preRollSeconds.toFloat()) }
    var duration by remember { mutableFloatStateOf(settings.recordDurationSeconds.toFloat()) }
    
    var aiEnabled by remember { mutableStateOf(settings.aiEnabled) }
    var aiConfidence by remember { mutableFloatStateOf(settings.aiConfidenceThreshold) }
    var sampleRate by remember { mutableIntStateOf(settings.audioSampleRate) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            Text("Aufnahme-Schwellenwert: ${String.format(Locale.getDefault(), "%.1f", dbThreshold)} dB")
            Slider(
                value = dbThreshold,
                onValueChange = { dbThreshold = it },
                onValueChangeFinished = { settings.dbThreshold = dbThreshold },
                valueRange = 30f..100f
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text("Pre-Roll (Sekunden): ${preRoll.toInt()}s")
            Slider(
                value = preRoll,
                onValueChange = { preRoll = it },
                onValueChangeFinished = { settings.preRollSeconds = preRoll.toInt() },
                valueRange = 0f..5f,
                steps = 4
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text("Aufnahmedauer (Sekunden): ${duration.toInt()}s")
            Slider(
                value = duration,
                onValueChange = { duration = it },
                onValueChangeFinished = { settings.recordDurationSeconds = duration.toInt() },
                valueRange = 1f..10f,
                steps = 8
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("KI-Erkennung aktivieren", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = aiEnabled, onCheckedChange = { 
                    aiEnabled = it
                    settings.aiEnabled = it
                })
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

            Spacer(modifier = Modifier.height(16.dp))

            Text("Abtastrate (Sample Rate): $sampleRate Hz")
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                FilterChip(
                    selected = sampleRate == 16000,
                    onClick = { sampleRate = 16000; settings.audioSampleRate = 16000 },
                    label = { Text("16000 Hz (KI Opt.)") }
                )
                FilterChip(
                    selected = sampleRate == 44100,
                    onClick = { sampleRate = 44100; settings.audioSampleRate = 44100 },
                    label = { Text("44100 Hz (Qualität)") }
                )
            }
        }
    }
}
