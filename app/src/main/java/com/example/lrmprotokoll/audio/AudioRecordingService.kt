package com.example.lrmprotokoll.audio

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.alert.AlarmCoordinator
import com.example.lrmprotokoll.alert.heartbeat.HeartbeatPlanung
import com.example.lrmprotokoll.data.LevelSource
import com.example.lrmprotokoll.data.NoiseRecord
import com.example.lrmprotokoll.data.SettingsManager
import com.example.lrmprotokoll.drive.LevelSampleCollector
import com.example.lrmprotokoll.meter.BoundDevice
import com.example.lrmprotokoll.meter.ConnectionState
import com.example.lrmprotokoll.meter.ConnectionSupervisor
import com.example.lrmprotokoll.meter.MeterTransport
import com.example.lrmprotokoll.meter.label
import com.example.lrmprotokoll.messreihe.RetentionPlanung
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val NOTIFICATION_ID = 1
private const val NOTIFICATION_CHANNEL_ID = "noise_monitoring_channel"

/**
 * Steuert, ob dieser Start auch die Mikrofon-Ueberwachung anstossen soll. Fehlt das Extra
 * (z. B. wenn MeterScreen den Dienst nur startet, damit ein frisch gepinntes Geraet unter dem
 * Schutz des Foreground Service verbunden wird), bleibt die Aufnahme unberuehrt - das Koppeln
 * eines Messgeraets soll nicht ungefragt die Mikrofon-Schwellwertueberwachung mitstarten.
 */
const val EXTRA_START_AUDIO_MONITORING = "start_audio_monitoring"

class AudioRecordingService : LifecycleService() {

    companion object {
        // M7c Aufgabe 1: Live-Status-Dashboard braucht einen echten, beobachtbaren "laeuft
        // der Dienst"-Zustand statt des bisherigen einmaligen ActivityManager.getRunningServices()
        // -Polls (Bestandsaufnahme-Befund 4.1/Verbesserungsvorschlag 1) - true, sobald der
        // Foreground-Zustand steht (deckt sowohl reine Mikrofon- als auch Messgeraet-Ueberwachung
        // ab), false nach explizitem Stop oder onDestroy.
        private val _laeuft = MutableStateFlow(false)
        val laeuft: StateFlow<Boolean> = _laeuft.asStateFlow()

        /** Nur fuer Compose-UI-Tests, die den Servicestart nicht real ausloesen koennen (kein
         * Mikrofon unter Robolectric) - internal statt private, damit das Testmodul zugreifen
         * kann, ohne den Setter Teil der eigentlichen Produktions-API zu machen. */
        internal fun testSetzeLaeuft(wert: Boolean) { _laeuft.value = wert }
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var isForegroundActive = false
    private var isRunning = false
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bytesPerSample = 2
    private var bufferSize = 0

    private lateinit var settingsManager: SettingsManager
    private lateinit var connectionSupervisor: ConnectionSupervisor
    private lateinit var alarmCoordinator: AlarmCoordinator
    private lateinit var meterTransport: MeterTransport
    private lateinit var levelSampleCollector: LevelSampleCollector
    private lateinit var diagnosticsReporter: com.example.lrmprotokoll.diagnose.DiagnosticsReporter
    private var classifier: SoundClassifier? = null

    private var rollingBuffer: ByteArray = ByteArray(0)
    private var writeHead = 0
    private var isBufferFull = false

    private lateinit var measurementRecorder: com.example.lrmprotokoll.messreihe.MeasurementRecorder

    /**
     * Der zuletzt eingetroffene Messgeraet-Frame, solange die Verbindung tatsaechlich auf
     * STREAMING steht - sonst `null` (Plan 4.5: "sonst auf die bisherige Mikrofonberechnung
     * zurueckfallen"). Wird bewusst NICHT aus [meterTransport.lastFrameAt] plus einer eigenen
     * Alterspruefung hergeleitet: Die Staleness-Schwelle dafuer legt bereits
     * [com.example.lrmprotokoll.meter.ConnectionSupervisor] fest (Plan 5.1) - eine zweite,
     * eigene Schwelle hier wuerde nur auseinanderlaufen koennen.
     */
    @Volatile private var letzterMeterFrame: com.example.lrmprotokoll.meter.MeterFrame? = null

    override fun onCreate() {
        super.onCreate()
        val container = (application as LaermprotokollApp).container
        settingsManager = container.settingsManager
        connectionSupervisor = container.connectionSupervisor
        alarmCoordinator = container.alarmCoordinator
        meterTransport = container.meterTransport
        levelSampleCollector = container.levelSampleCollector
        diagnosticsReporter = container.diagnosticsReporter
        classifier = NoiseClassifier(applicationContext)
        diagnosticsReporter.breadcrumb("AudioService", "AudioRecordingService erstellt")

        // M7b: Pegelwerte des Messgeraets fuer den Drive-Sync einsammeln - unabhaengig vom
        // Aufnahme-Trigger, der weiterhin am Mikrofon haengt (Trigger-Umstellung ist M4). Nur
        // wenn Drive-Sync eingeschaltet ist, damit ohne aktivierten Sync kein Puffer waechst,
        // den niemand ausliest.
        measurementRecorder = container.measurementRecorder

        lifecycleScope.launch {
            meterTransport.frames.collect { frame ->
                letzterMeterFrame = frame
                if (settingsManager.driveSyncEnabled) {
                    levelSampleCollector.pegel(LevelSource.PCE_323, frame.level, frame.receivedAt)
                }
            }
        }
        updateRollingBuffer()

        lifecycleScope.launch {
            connectionSupervisor.state.collect { state ->
                updateNotification(state)
                // Ein Frame gilt nur als "aktuell", solange die Verbindung wirklich steht - sonst
                // wuerde der letzte Wert vor einem Abbruch beliebig lange als Live-Pegel
                // durchgereicht, obwohl laengst nichts mehr ankommt.
                if (state != ConnectionState.STREAMING) letzterMeterFrame = null
            }
        }
    }

    /**
     * Verbindung zum PCE-323 gehoert in den Foreground Service, nicht in die UI (PROMPT_M3
     * Aufgabe 3): sie muss eine geschlossene UI und Konfigurationswechsel ueberleben. Bei jedem
     * [onStartCommand] neu geprueft statt nur in [onCreate] - so wird auch ein waehrend
     * laufendem Dienst neu gepinntes Geraet aufgenommen, ohne dass die Aufnahme neu gestartet
     * werden muss. [ConnectionSupervisor.start] ist ein No-Op, wenn bereits genau dieses Geraet
     * ueberwacht wird. Ohne gepinntes Geraet (Plan Abschnitt 6) passiert nichts.
     */
    private fun ensureMeterMonitoringStarted() {
        val address = settingsManager.meterDeviceAddress ?: return
        val hasBluetoothConnect = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasBluetoothConnect) {
            Log.w("AudioRecordingService", "BLUETOOTH_CONNECT Berechtigung fehlt - Meter-Monitoring wird übersprungen")
            return
        }
        val device = BoundDevice(address, settingsManager.meterDeviceName ?: address)
        connectionSupervisor.start(device)

        // Die Alarmierung haengt am selben Lebenszyklus wie die Verbindung: Sie beobachtet
        // deren Zustand und ist ohne sie gegenstandslos. Beide Aufrufe sind No-Ops, wenn
        // bereits gestartet.
        if (settingsManager.alarmierungAktiv) {
            alarmCoordinator.start()
            HeartbeatPlanung.plane(applicationContext)
        }

        // M4: die Messreihe braucht zwingend ein Geraet (SessionEntity.deviceAddress) - anders
        // als der Drive-Sync gehoert sie deshalb hierher und nicht in eine eigene, ungegatete
        // Methode.
        measurementRecorder.start(device)
    }

    /**
     * Bewusst NICHT Teil von [ensureMeterMonitoringStarted]: Der Drive-Sync soll laut Plan 8.4
     * auch ganz ohne gepinntes Messgeraet allein mit Mikrofonwerten laufen. Waere dieser Aufruf
     * dort verschachtelt, wuerde ihn der fruehe Rueckgabe-Pfad ohne gepinntes Geraet nie
     * erreichen - der Puffer in [levelSampleCollector] wuerde befuellt, aber nie geleert, weil
     * die periodische Flush-Schleife nie gestartet waere.
     */
    private fun ensureDriveSyncStarted() {
        if (!settingsManager.driveSyncEnabled) return
        levelSampleCollector.start()
        com.example.lrmprotokoll.drive.DriveSyncPlanung.plane(applicationContext)
    }

    /**
     * Plan Abschnitt 6: das Diagnose-Log ist standardmaessig aus, der taegliche
     * Bereinigungs-Job (7-Tage-Loeschung) soll deshalb nur laufen, wenn ueberhaupt geschrieben
     * wird - anders als der M4-Retention-Job, der immer laeuft.
     */
    private fun ensureDiagnosticLoggingStarted() {
        if (!settingsManager.diagnoseLoggingAktiv) return
        com.example.lrmprotokoll.diagnose.DiagnosticLogCleanupPlanung.plane(applicationContext)
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
            // Expliziter Nutzerstop: die Flags fuer die Neustart-Wiederaufnahme (Plan Abschnitt
            // 5.4) muessen hier zurueckgesetzt werden, sonst wuerde ein spaeterer Geraeteneustart
            // etwas reaktivieren, das der Nutzer bewusst beendet hat.
            settingsManager.monitoringWasActive = false
            settingsManager.audioMonitoringWasActive = false
            // Erst hier den Heartbeat abbestellen: Der Nutzer hat die Ueberwachung bewusst
            // beendet, ein ausbleibendes Lebenszeichen waere ab jetzt kein Ausfall mehr.
            HeartbeatPlanung.stoppe(applicationContext)
            com.example.lrmprotokoll.drive.DriveSyncPlanung.stoppe(applicationContext)
            com.example.lrmprotokoll.diagnose.DiagnosticLogCleanupPlanung.stoppe(applicationContext)
            _laeuft.value = false
            stopSelf()
            return START_NOT_STICKY
        }

        if (!isForegroundActive) {
            isForegroundActive = true
            startForegroundService()
            settingsManager.monitoringWasActive = true
            _laeuft.value = true
        }
        if (!isRunning && intent?.getBooleanExtra(EXTRA_START_AUDIO_MONITORING, false) == true) {
            isRunning = true
            startMonitoring()
            settingsManager.audioMonitoringWasActive = true
        }
        ensureMeterMonitoringStarted()
        ensureDriveSyncStarted()
        ensureDiagnosticLoggingStarted()
        // M4: unabhaengig von jeder Einstellung geplant (anders als Heartbeat/Drive-Sync) -
        // alte Rohwerte sollen verdichtet werden, sobald der Dienst ueberhaupt einmal laeuft,
        // unabhaengig davon, ob gerade ein Messgeraet gepinnt oder Drive-Sync aktiv ist.
        com.example.lrmprotokoll.messreihe.RetentionPlanung.plane(applicationContext)
        return START_STICKY
    }

    private fun startForegroundService() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Lärm-Monitoring Dienst",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val hasBluetoothConnect = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED

        val hasRecordAudio = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        var serviceType = 0
        if (hasRecordAudio) {
            serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (hasBluetoothConnect && settingsManager.meterDeviceAddress != null) {
            serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }

        try {
            if (serviceType != 0) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(connectionSupervisor.state.value),
                    serviceType
                )
            } else {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(connectionSupervisor.state.value)
                )
            }
            diagnosticsReporter.breadcrumb("AudioService", "Foreground-Service erfolgreich gestartet (types=$serviceType)")
        } catch (e: Throwable) {
            Log.e("AudioRecordingService", "Foreground Service konnte nicht mit Typen gestartet werden", e)
            diagnosticsReporter.report(
                code = com.example.lrmprotokoll.diagnose.DiagnosticCode.AUDIO_FOREGROUND_SERVICE_FAILED,
                component = "AudioRecordingService",
                operation = "startForegroundService",
                severity = com.example.lrmprotokoll.diagnose.DiagnosticSeverity.ERROR,
                cause = e
            )
            try {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(connectionSupervisor.state.value)
                )
            } catch (fallbackEx: Throwable) {
                Log.e("AudioRecordingService", "Fallback startForeground fehlgeschlagen", fallbackEx)
                stopSelf()
            }
        }
    }

    /**
     * Zeigt den Messgeraet-Verbindungszustand in der Dauer-Notification (Plan Abschnitt 5.4):
     * sonst ist von aussen nicht erkennbar, ob die Ueberwachung wirklich laeuft oder der
     * Dienst nur ohne Datenfluss dasteht. Nur relevant, solange ueberhaupt ein Geraet gepinnt
     * ist - ohne Pinning bleibt der Supervisor bei IDLE und die Zeile wird nicht angezeigt.
     */
    private fun updateNotification(state: ConnectionState) {
        if (!isForegroundActive) return // Notification-Kanal existiert erst nach startForegroundService()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(meterState: ConnectionState): Notification {
        val stopIntent = Intent(this, AudioRecordingService::class.java).apply {
            action = "STOP_SERVICE"
        }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        val contentText = if (settingsManager.meterDeviceAddress != null) {
            "Überwacht die Umgebungslautstärke. Messgerät: ${meterState.label()}"
        } else {
            "Die App überwacht die Umgebungslautstärke im Hintergrund."
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Lärm-Monitoring aktiv")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stoppen", stopPendingIntent)
            .build()
    }

    private fun startMonitoring() {
        serviceScope.launch {
            if (ActivityCompat.checkSelfPermission(this@AudioRecordingService, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Log.e("AudioRecordingService", "Mikrofon-Berechtigung nicht erteilt - Monitoring wird nicht gestartet")
                isRunning = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }

            val sampleRate = settingsManager.audioSampleRate
            bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            diagnosticsReporter.breadcrumb("AudioService", "Mikrofon-Monitoring gestartet (sampleRate=$sampleRate)")

            val audioRecord = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
            } catch (e: Exception) {
                Log.e("AudioRecordingService", "AudioRecord konnte nicht instanziiert werden", e)
                diagnosticsReporter.report(
                    code = com.example.lrmprotokoll.diagnose.DiagnosticCode.AUDIO_INIT_FAILED,
                    component = "AudioRecordingService",
                    operation = "startMonitoring",
                    severity = com.example.lrmprotokoll.diagnose.DiagnosticSeverity.ERROR,
                    cause = e
                )
                isRunning = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("AudioRecordingService", "AudioRecord initialization failed")
                diagnosticsReporter.report(
                    code = com.example.lrmprotokoll.diagnose.DiagnosticCode.AUDIO_INIT_FAILED,
                    component = "AudioRecordingService",
                    operation = "startMonitoring",
                    severity = com.example.lrmprotokoll.diagnose.DiagnosticSeverity.ERROR,
                    message = "AudioRecord Initialisierung fehlgeschlagen (sampleRate=$sampleRate, bufferSize=$bufferSize)",
                    details = mapOf("sampleRate" to sampleRate, "bufferSize" to bufferSize)
                )
                isRunning = false
                audioRecord.release()
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

                    // M7b-Nachtrag: dieser Aufruf fehlte bislang komplett - der Mikrofon-Pfad hat
                    // nie in den Drive-Sync-Puffer geschrieben, obwohl der PCE-323-Pfad das schon
                    // seit M7b tat. Ohne Messgeraet blieb der Sync damit lautlos leer, obwohl er
                    // laut Plan genau dafuer auch allein mit Mikrofonwerten laufen sollte.
                    if (settingsManager.driveSyncEnabled) {
                        levelSampleCollector.pegel(LevelSource.MIKROFON, currentDb, Instant.now())
                    }

                    val cal = java.util.Calendar.getInstance()
                    val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
                    val start = settingsManager.quietHoursStartHour
                    val end = settingsManager.quietHoursEndHour
                    val isQuiet = if (settingsManager.quietHoursEnabled) {
                        if (start <= end) (hour >= start && hour < end)
                        else (hour >= start || hour < end)
                    } else false

                    val activeSchwelle = if (isQuiet) settingsManager.quietHoursThreshold else settingsManager.dbThreshold

                    val auswertung = com.example.lrmprotokoll.messreihe.MeterTriggerSource.auswerten(
                        letzterMeterFrame = letzterMeterFrame,
                        mikrofonDb = currentDb,
                        mikrofonSchwelle = activeSchwelle,
                    )
                    if (auswertung.ausgeloest) {
                        Log.d(
                            "AudioRecordingService",
                            "Schwelle überschritten: ${String.format("%.1f", auswertung.pegel)} dB " +
                                "(Quelle: ${if (auswertung.meterConnected) "Messgerät" else "Mikrofon"}, Ruhezeit=$isQuiet)",
                        )
                        saveRecording(audioRecord, maxAmplitude.toDouble(), currentDb, sampleRate, auswertung, isQuiet)
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

    private suspend fun saveRecording(
        audioRecord: AudioRecord,
        amplitude: Double,
        dbValue: Double,
        sampleRate: Int,
        auswertung: com.example.lrmprotokoll.messreihe.MeterTriggerSource.Auswertung,
        isQuiet: Boolean,
    ) {
        val timestamp = System.currentTimeMillis()
        val fileName = "noise_$timestamp.wav"
        val file = File(getExternalFilesDir(null), fileName)
        
        val durationMs = settingsManager.recordDurationSeconds * 1000L
        try {
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
        } catch (e: Throwable) {
            Log.e("AudioRecordingService", "Fehler beim Speichern der WAV-Datei", e)
            diagnosticsReporter.report(
                code = com.example.lrmprotokoll.diagnose.DiagnosticCode.AUDIO_FILE_WRITE_FAILED,
                component = "AudioRecordingService",
                operation = "saveRecording",
                severity = com.example.lrmprotokoll.diagnose.DiagnosticSeverity.ERROR,
                cause = e,
                details = mapOf("fileName" to fileName)
            )
            return
        }

        // Klassifikator-Aufruf darf die Aufnahme nie verhindern (Prompt B-11, Problem 2):
        // classifySafely() faengt jede Ausnahme ab und liefert dann nur ein fehlendes Label.
        val detected = classifySafely(classifier, settingsManager.aiEnabled, file)

        val dao = (application as LaermprotokollApp).container.database.noiseDao()
        dao.insert(NoiseRecord(
            timestamp = timestamp, 
            amplitude = amplitude, 
            dbValue = dbValue,
            filePath = file.absolutePath,
            detectedLabel = detected,
            calibratedDbA = auswertung.calibratedDbA,
            meterWeighting = auswertung.meterWeighting,
            meterConnected = auswertung.meterConnected,
            isQuietHour = isQuiet,
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
        _laeuft.value = false
        diagnosticsReporter.breadcrumb("AudioService", "AudioRecordingService wird beendet")
        connectionSupervisor.stop()
        // Der Koordinator wird gestoppt, der Heartbeat aber NICHT: Er meldet "App und Gerät
        // leben" und ist gerade dann aussagekraeftig, wenn dieser Dienst nicht mehr laeuft. Er
        // prueft selbst, ob ueberwacht werden soll, und schweigt sonst - abgeschaltet wird er
        // beim expliziten Nutzerstop in onStartCommand.
        alarmCoordinator.stop()
        levelSampleCollector.stop()
        measurementRecorder.stop()
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        return super.onBind(intent)
    }
}
