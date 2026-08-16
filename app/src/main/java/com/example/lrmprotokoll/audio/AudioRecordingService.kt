package com.example.lrmprotokoll.audio

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.data.NoiseRecord
import com.example.lrmprotokoll.data.SettingsManager
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioRecordingService : LifecycleService() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    
    private var isRunning = false
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bytesPerSample = 2 
    private var bufferSize = 0

    private lateinit var settingsManager: SettingsManager
    private var classifier: NoiseClassifier? = null
    
    private var rollingBuffer: ByteArray = ByteArray(0)
    private var writeHead = 0
    private var isBufferFull = false

    override fun onCreate() {
        super.onCreate()
        settingsManager = (application as LaermprotokollApp).container.settingsManager
        classifier = NoiseClassifier(applicationContext)
        updateRollingBuffer()
    }

    private fun updateRollingBuffer() {
        val sampleRate = settingsManager.audioSampleRate
        val size = sampleRate * settingsManager.preRollSeconds * bytesPerSample
        if (rollingBuffer.size != size) {
            rollingBuffer = ByteArray(size)
            writeHead = 0
            isBufferFull = false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == "STOP_SERVICE") {
            stopSelf()
            return START_NOT_STICKY
        }
        
        if (!isRunning) {
            isRunning = true
            startForegroundService()
            startMonitoring()
        }
        return START_STICKY
    }

    private fun startForegroundService() {
        val channelId = "noise_monitoring_channel"
        val channel = NotificationChannel(
            channelId,
            "Lärm-Monitoring Dienst",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        val stopIntent = Intent(this, AudioRecordingService::class.java).apply {
            action = "STOP_SERVICE"
        }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Lärm-Monitoring aktiv")
            .setContentText("Die App überwacht die Umgebungslautstärke im Hintergrund.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stoppen", stopPendingIntent)
            .build()

        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    private fun startMonitoring() {
        serviceScope.launch {
            val sampleRate = settingsManager.audioSampleRate
            bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            
            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("AudioRecordingService", "AudioRecord initialization failed")
                isRunning = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }

            audioRecord.startRecording()
            val buffer = ShortArray(bufferSize / 2)
            val tempByteBuffer = ByteBuffer.allocate(bufferSize).order(ByteOrder.LITTLE_ENDIAN)

            while (isRunning) {
                val readSize = audioRecord.read(buffer, 0, buffer.size)
                if (readSize > 0) {
                    var maxAmplitude = 0
                    tempByteBuffer.clear()
                    for (i in 0 until readSize) {
                        val sample = buffer[i]
                        val absValue = Math.abs(sample.toInt())
                        if (absValue > maxAmplitude) maxAmplitude = absValue
                        tempByteBuffer.putShort(sample)
                    }

                    updateRollingBuffer()
                    writeToRollingBuffer(tempByteBuffer.array(), readSize * 2)

                    val currentDb = calculateDb(buffer, readSize)

                    // Trigger based on dB threshold
                    if (currentDb > settingsManager.dbThreshold) {
                        Log.d("AudioRecordingService", "dB Threshold exceeded: ${String.format("%.1f", currentDb)} dB")
                        saveRecording(audioRecord, maxAmplitude.toDouble(), currentDb, sampleRate)
                    }
                }
                delay(50)
            }
            audioRecord.stop()
            audioRecord.release()
        }
    }

    private fun calculateDb(buffer: ShortArray, readSize: Int): Double {
        if (readSize <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until readSize) {
            sum += buffer[i].toDouble() * buffer[i].toDouble()
        }
        val rms = Math.sqrt(sum / readSize)
        val db = 20 * Math.log10(rms / 32767.0) + 100.0
        return if (db < 0) 0.0 else db
    }

    private fun writeToRollingBuffer(data: ByteArray, size: Int) {
        synchronized(rollingBuffer) {
            if (rollingBuffer.isEmpty()) return
            if (size > rollingBuffer.size) {
                System.arraycopy(data, size - rollingBuffer.size, rollingBuffer, 0, rollingBuffer.size)
                writeHead = 0
                isBufferFull = true
                return
            }
            if (writeHead + size <= rollingBuffer.size) {
                System.arraycopy(data, 0, rollingBuffer, writeHead, size)
                writeHead += size
            } else {
                val firstPart = rollingBuffer.size - writeHead
                System.arraycopy(data, 0, rollingBuffer, writeHead, firstPart)
                val secondPart = size - firstPart
                System.arraycopy(data, firstPart, rollingBuffer, 0, secondPart)
                writeHead = secondPart
                isBufferFull = true
            }
            if (writeHead >= rollingBuffer.size) {
                writeHead = 0
                isBufferFull = true
            }
        }
    }

    private fun getPreRollData(): ByteArray {
        synchronized(rollingBuffer) {
            if (rollingBuffer.isEmpty()) return ByteArray(0)
            val result = ByteArray(if (isBufferFull) rollingBuffer.size else writeHead)
            if (!isBufferFull) {
                System.arraycopy(rollingBuffer, 0, result, 0, writeHead)
            } else {
                val part1 = rollingBuffer.size - writeHead
                System.arraycopy(rollingBuffer, writeHead, result, 0, part1)
                System.arraycopy(rollingBuffer, 0, result, part1, writeHead)
            }
            return result
        }
    }

    private suspend fun saveRecording(audioRecord: AudioRecord, amplitude: Double, dbValue: Double, sampleRate: Int) {
        val timestamp = System.currentTimeMillis()
        val fileName = "noise_$timestamp.wav"
        val file = File(getExternalFilesDir(null), fileName)
        
        val durationMs = settingsManager.recordDurationSeconds * 1000L
        val outputStream = FileOutputStream(file)
        
        writeWavHeader(outputStream, channelConfig, sampleRate, audioFormat, 0)

        val preRollData = getPreRollData()
        outputStream.write(preRollData)
        var totalDataLen = preRollData.size.toLong()

        val buffer = ShortArray(bufferSize / 2)
        val tempByteBuffer = ByteBuffer.allocate(bufferSize).order(ByteOrder.LITTLE_ENDIAN)
        val startTime = System.currentTimeMillis()
        
        while (System.currentTimeMillis() - startTime < durationMs && isRunning) {
            val read = audioRecord.read(buffer, 0, buffer.size)
            if (read > 0) {
                tempByteBuffer.clear()
                for (i in 0 until read) {
                    tempByteBuffer.putShort(buffer[i])
                }
                outputStream.write(tempByteBuffer.array(), 0, read * 2)
                totalDataLen += (read * 2)
                writeToRollingBuffer(tempByteBuffer.array(), read * 2)
            }
        }
        
        outputStream.close()
        updateWavHeader(file, totalDataLen)

        // KI Klassifizierung nur wenn aktiviert
        val detected = if (settingsManager.aiEnabled) {
            classifier?.classify(file)
        } else {
            null
        }

        val dao = (application as LaermprotokollApp).container.database.noiseDao()
        dao.insert(NoiseRecord(
            timestamp = timestamp, 
            amplitude = amplitude, 
            dbValue = dbValue,
            filePath = file.absolutePath,
            detectedLabel = detected
        ))
    }

    private fun writeWavHeader(out: FileOutputStream, channelConfig: Int, sampleRate: Int, audioFormat: Int, dataLength: Long) {
        val channels = if (channelConfig == AudioFormat.CHANNEL_IN_MONO) 1 else 2
        val bitsPerSample = if (audioFormat == AudioFormat.ENCODING_PCM_16BIT) 16 else 8
        val byteRate = (sampleRate * channels * bitsPerSample / 8).toLong()
        val totalLength = dataLength + 36
        
        val header = ByteBuffer.allocate(44).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            put("RIFF".toByteArray())
            putInt(totalLength.toInt())
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16) // Subchunk1Size
            putShort(1.toShort()) // AudioFormat (PCM = 1)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate.toInt())
            putShort((channels * bitsPerSample / 8).toShort()) // BlockAlign
            putShort(bitsPerSample.toShort())
            put("data".toByteArray())
            putInt(dataLength.toInt())
        }
        out.write(header.array())
    }

    private fun updateWavHeader(file: File, dataLength: Long) {
        val raf = java.io.RandomAccessFile(file, "rw")
        val totalLength = dataLength + 36
        raf.seek(4)
        raf.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(totalLength.toInt()).array())
        raf.seek(40)
        raf.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(dataLength.toInt()).array())
        raf.close()
    }

    override fun onDestroy() {
        isRunning = false
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        return super.onBind(intent)
    }
}
