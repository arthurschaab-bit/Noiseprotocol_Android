package com.example.lrmprotokoll.ui

import android.media.MediaPlayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

val PauseIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Pause",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(6f, 19f)
            horizontalLineToRelative(4f)
            verticalLineTo(5f)
            horizontalLineTo(6f)
            verticalLineToRelative(14f)
            close()
            moveTo(14f, 5f)
            verticalLineToRelative(14f)
            horizontalLineToRelative(4f)
            verticalLineTo(5f)
            horizontalLineToRelative(-4f)
            close()
        }
    }.build()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioPlayerScreen(filePath: String, onBack: () -> Unit) {
    val file = remember(filePath) { File(filePath) }
    var amplitudes by remember(filePath) { mutableStateOf<List<Float>>(emptyList()) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentProgress by remember { mutableFloatStateOf(0f) }
    var isPrepared by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    
    // Amplituden asynchron im Hintergrund laden, um die UI-Komposition nicht durch Datei-I/O zu blockieren
    LaunchedEffect(filePath) {
        amplitudes = withContext(Dispatchers.IO) {
            loadAmplitudes(file)
        }
    }

    var mediaPlayerRef by remember { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(filePath) {
        val player = MediaPlayer()
        mediaPlayerRef = player
        var isDisposed = false

        try {
            if (!file.exists()) {
                loadError = "Audiodatei existiert nicht mehr."
            } else if (file.length() < 44) {
                loadError = "Audiodatei ist beschädigt (zu kurz)."
            } else {
                player.setDataSource(filePath)
                player.setOnPreparedListener {
                    if (!isDisposed) {
                        isPrepared = true
                    }
                }
                player.setOnCompletionListener {
                    isPlaying = false
                    currentProgress = 1f
                }
                player.setOnErrorListener { _, what, extra ->
                    if (!isDisposed) {
                        loadError = "Fehler bei der Audiowiedergabe ($what, $extra)"
                        isPlaying = false
                        isPrepared = false
                    }
                    true // Fehler wurde behandelt
                }
                // Asynchrones Vorbereiten verhindert ANRs und Blockieren des Main/UI-Threads
                player.prepareAsync()
            }
        } catch (e: Exception) {
            loadError = "Audiodatei konnte nicht geladen werden: ${e.message ?: e.javaClass.simpleName}"
        }

        onDispose {
            isDisposed = true
            isPrepared = false
            isPlaying = false
            mediaPlayerRef = null
            // MediaPlayer-Ressourcen ausnahmslos und sicher freigeben (verhindert Leaks und Zombie-Prozesse)
            runCatching {
                if (player.isPlaying) {
                    player.stop()
                }
                player.reset()
                player.release()
            }
        }
    }

    // Fortschritts-Tracker während aktiver Wiedergabe
    LaunchedEffect(isPlaying, isPrepared) {
        val player = mediaPlayerRef
        if (player != null && isPlaying && isPrepared) {
            try {
                player.start()
                while (player.isPlaying) {
                    val duration = player.duration
                    if (duration > 0) {
                        currentProgress = (player.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
                    }
                    delay(50)
                }
                if (!player.isPlaying && currentProgress < 0.99f) {
                    isPlaying = false
                }
            } catch (_: Exception) {
                isPlaying = false
            }
        } else if (player != null && !isPlaying && isPrepared) {
            runCatching {
                if (player.isPlaying) {
                    player.pause()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Aufnahme abspielen") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
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

            val error = loadError
            if (error != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = "Fehler",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = onBack,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Zurück")
                        }
                    }
                }
            } else {
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
                        val player = mediaPlayerRef
                        if (player != null && isPrepared) {
                            if (currentProgress >= 0.99f) {
                                runCatching { player.seekTo(0) }
                                currentProgress = 0f
                            }
                            isPlaying = !isPlaying
                        }
                    },
                    modifier = Modifier.size(64.dp),
                    enabled = isPrepared
                ) {
                    Icon(
                        imageVector = if (isPlaying) PauseIcon else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Abspielen",
                        modifier = Modifier.size(48.dp)
                    )
                }
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
    
    val bytes = try {
        file.readBytes()
    } catch (_: Exception) {
        return emptyList()
    }
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
