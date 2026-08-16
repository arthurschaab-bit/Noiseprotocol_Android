package com.example.lrmprotokoll.ui

import android.media.MediaPlayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioPlayerScreen(filePath: String, onBack: () -> Unit) {
    val file = File(filePath)
    val amplitudes = remember(filePath) { loadAmplitudes(file) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentProgress by remember { mutableFloatStateOf(0f) }
    
    val mediaPlayer = remember { MediaPlayer() }
    
    DisposableEffect(filePath) {
        mediaPlayer.setDataSource(filePath)
        mediaPlayer.prepare()
        onDispose {
            mediaPlayer.release()
        }
    }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            mediaPlayer.start()
            while (mediaPlayer.isPlaying) {
                currentProgress = mediaPlayer.currentPosition.toFloat() / mediaPlayer.duration
                delay(50)
            }
            isPlaying = false
            currentProgress = 1f
        } else {
            if (mediaPlayer.isPlaying) mediaPlayer.pause()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Aufnahme abspielen") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Zurück")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(file.name, style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(32.dp))
            
            // Wellenform-Anzeige
            WaveformDisplay(
                amplitudes = amplitudes,
                progress = currentProgress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            IconButton(
                onClick = { 
                    if (currentProgress >= 0.99f) {
                        mediaPlayer.seekTo(0)
                        currentProgress = 0f
                    }
                    isPlaying = !isPlaying 
                },
                modifier = Modifier.size(64.dp)
            ) {
                Icon(
                    if (isPlaying) Icons.Default.PlayArrow else Icons.Default.PlayArrow, // TODO: Add Pause Icon
                    contentDescription = if (isPlaying) "Pause" else "Abspielen",
                    modifier = Modifier.size(48.dp)
                )
            }
        }
    }
}

@Composable
fun WaveformDisplay(amplitudes: List<Float>, progress: Float, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val progressColor = MaterialTheme.colorScheme.secondary
    
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val centerY = height / 2
        
        if (amplitudes.isEmpty()) return@Canvas
        
        val step = width / amplitudes.size
        
        amplitudes.forEachIndexed { index, amplitude ->
            val x = index * step
            val barHeight = amplitude * height * 0.8f
            val isPlayed = (index.toFloat() / amplitudes.size) < progress
            
            drawLine(
                color = if (isPlayed) progressColor else color,
                start = Offset(x, centerY - barHeight / 2),
                end = Offset(x, centerY + barHeight / 2),
                strokeWidth = step * 0.8f
            )
        }
        
        // Progress Line
        drawLine(
            color = Color.Red,
            start = Offset(progress * width, 0f),
            end = Offset(progress * width, height),
            strokeWidth = 2.dp.toPx()
        )
    }
}

fun loadAmplitudes(file: File): List<Float> {
    if (!file.exists()) return emptyList()
    
    val bytes = file.readBytes()
    if (bytes.size < 44) return emptyList()
    
    // Einfache PCM-Extraktion (16-bit Mono angenommen)
    val pcmData = bytes.sliceArray(44 until bytes.size)
    val shortBuffer = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    
    val result = mutableListOf<Float>()
    val step = maxOf(1, shortBuffer.limit() / 100) // 100 Punkte für die Anzeige
    
    for (i in 0 until shortBuffer.limit() step step) {
        var max = 0
        for (j in 0 until step) {
            if (i + j < shortBuffer.limit()) {
                val sample = Math.abs(shortBuffer.get(i + j).toInt())
                if (sample > max) max = sample
            }
        }
        result.add(max.toFloat() / Short.MAX_VALUE)
    }
    return result
}
