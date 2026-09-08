package com.example.lrmprotokoll.audio

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
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
import com.example.lrmprotokoll.widget.NoiseMonitoringWidgetProvider
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
 * Steuert, ob dieser Start auch die Mikrofon-Ueberwachung anstossen soll. Fehlt das Extra,
 * bleibt die Aufnahme nur dann aus, wenn sie vor dem Service-Recreate ebenfalls aus war.
 */
const val EXTRA_START_AUDIO_MONITORING = "start_audio_monitoring"
const val ACTION_START_AUDIO_MONITORING = "com.example.lrmprotokoll.START_AUDIO_MONITORING"
const val ACTION_STOP_AUDIO_RECORDING = "com.example.lrmprotokoll.STOP_AUDIO_RECORDING"
const val ACTION_STOP_SERVICE = "STOP_SERVICE"

class AudioRecordingService : LifecycleService() {

    companion object {
        private val _laeuft = MutableStateFlow(false)
        val laeuft: StateFlow<Boolean> = _laeuft.asStateFlow()

        /** Echter AudioRecord-Laufzeitzustand; nicht mit dem Foreground-Service verwechseln. */
        private val _audioAufnahmeAktiv = MutableStateFlow(false)
        val audioAufnahmeAktiv: StateFlow<Boolean> = _audioAufnahmeAktiv.asStateFlow()

        private val _currentMicDb = MutableStateFlow<Double?>(null)
        val currentMicDb: StateFlow<Double?> = _currentMicDb.asStateFlow()

        private val _laufendesFormat = MutableStateFlow<Aufnahmeformat?>(null)
        val laufendesFormat: StateFlow<Aufnahmeformat?> = _laufendesFormat.asStateFlow()

        internal fun testSetzeLaeuft(wert: Boolean) { _laeuft.value = wert }
        internal fun testSetzeAudioAufnahmeAktiv(wert: Boolean) { _audioAufnahmeAktiv.value = wert }
        internal fun testSetzeCurrentMicDb(wert: Double?) { _currentMicDb.value = wert }
        internal fun testSetzeLaufendesFormat(wert: Aufnahmeformat?) { _laufendesFormat.value = wert }
    }

    data class Aufnahmeformat(val abtastrate: Int, val kanaele: Int)

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var isForegroundActive = false
    /** Soll die Audio-Schleife laufen? Der echte Zustand steht in [_audioAufnahmeAktiv]. */
    private var isRunning = false
    private var expliziterServiceStopAngefordert = false
    private var audioSollIstFehlerGemeldet = false

    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bytesPerSample = 2
    private var bufferSize = 0

    @Volatile private var aktiveAufnahmequelle: Int? = null
    @Volatile private var aktiveAbtastrate: Int? = null
    @Volatile private var aktiveKanalzahl: Int? = null
    @Volatile private var aktiveAgcAktiv: Boolean? = null

    private lateinit var settingsManager: SettingsManager
    private lateinit var connectionSupervisor: ConnectionSupervisor
    private lateinit var alarmCoordinator: AlarmCoordinator
    private lateinit var meterTransport: MeterTransport
    private lateinit var levelSampleCollector: LevelSampleCollector
    private lateinit var diagnosticsReporter: com.example.lrmprotokoll.diagnose.DiagnosticsReporter
    private var classifier: RohdatenClassifier? = null

    private var rollingBuffer: ByteArray = ByteArray(0)
    private var writeHead = 0
    private var isBufferFull = false

    private lateinit var measurementRecorder: com.example.lrmprotokoll.messreihe.MeasurementRecorder
    private lateinit var videoTonMitschnitt: com.example.lrmprotokoll.video.VideoTonMitschnitt

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
        measurementRecorder = container.measurementRecorder
        videoTonMitschnitt = container.videoTonMitschnitt

        diagnosticsReporter.breadcrumb(
            "AudioService",
            "AudioRecordingService erstellt",
            data = mapOf(
                "audioWarAktiv" to settingsManager.audioMonitoringWasActive,
                "monitoringWarAktiv" to settingsManager.monitoringWasActive,
                "recordWavAudio" to settingsManager.recordWavAudio,
                "meterAdresseVorhanden" to (settingsManager.meterDeviceAddress != null),
            ),
        )

        lifecycleScope.launch {
            meterTransport.frames.collect { frame ->
                letzterMeterFrame = frame
                if (settingsManager.driveSyncEnabled) {
                    levelSampleCollector.pegel(LevelSource.PCE_323, frame.level, frame.receivedAt)
                }
                pruefeSchwellenwertUndTrigger(meterFrame = frame, mikrofonDb = null)
            }
        }
        updateRollingBuffer()

        lifecycleScope.launch {
            connectionSupervisor.state.collect { state ->
                updateNotification(state)
                if (state != ConnectionState.STREAMING) letzterMeterFrame = null
            }
        }

        lifecycleScope.launch {
            while (isActive) {
                delay(5000L)
                if (isForegroundActive) {
                    pruefeAudioSollIstAbweichung()
                    pruefeStillenAusfall()
                    updateNotification(connectionSupervisor.state.value)
                    NoiseMonitoringWidgetProvider.updateAlleWidgets(applicationContext)
                }
            }
        }
    }

    private fun ensureMeterMonitoringStarted() {
        if (settingsManager.audioTriggerQuelle == "MIKROFON") {
            Log.d("AudioRecordingService", "Trigger-Quelle ist rein Mikrofon - Meter-Monitoring wird übersprungen")
            connectionSupervisor.stop()
            return
        }
        val address = settingsManager.meterDeviceAddress ?: return
        val hasBluetoothConnect = com.example.lrmprotokoll.meter.ble.BluetoothPermissions.hasConnectPermission(this)
        if (!hasBluetoothConnect) {
            Log.w("AudioRecordingService", "Bluetooth Berechtigung fehlt - Meter-Monitoring wird übersprungen")
            return
        }
        val device = BoundDevice(address, settingsManager.meterDeviceName ?: address)
        connectionSupervisor.start(device)

        if (settingsManager.alarmierungAktiv) {
            alarmCoordinator.start()
            HeartbeatPlanung.plane(applicationContext)
        }
        measurementRecorder.start(device)
    }

    private fun ensureDriveSyncStarted() {
        if (!settingsManager.driveSyncEnabled) return
        levelSampleCollector.start()
        com.example.lrmprotokoll.drive.DriveSyncPlanung.plane(applicationContext)
    }

    private fun ensureDiagnosticLoggingStarted() {
        if (!settingsManager.diagnoseLoggingAktiv) return
        com.example.lrmprotokoll.diagnose.DiagnosticLogCleanupPlanung.plane(applicationContext)
    }

    private fun updateRollingBuffer() {
        val sampleRate = aktiveAbtastrate ?: settingsManager.audioSampleRate
        val size = sampleRate * settingsManager.preRollSeconds * bytesPerSample
        if (rollingBuffer.size != size) {
            rollingBuffer = ByteArray(size)
            writeHead = 0
            isBufferFull = false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val action = intent?.action
        val expliziterAudioStart = intent?.getBooleanExtra(EXTRA_START_AUDIO_MONITORING, false) == true ||
            action == ACTION_START_AUDIO_MONITORING || action == "START_AUDIO_MONITORING"
        val audioWarVorherAktiv = settingsManager.audioMonitoringWasActive

        diagnosticsReporter.breadcrumb(
            "AudioService",
            "onStartCommand",
            data = mapOf(
                "action" to (action ?: "<null>"),
                "flags" to flags,
                "startId" to startId,
                "audioStartExtra" to (intent?.getBooleanExtra(EXTRA_START_AUDIO_MONITORING, false) == true),
                "audioWarVorherAktiv" to audioWarVorherAktiv,
                "audioSollLaufen" to isRunning,
                "audioIstAktiv" to _audioAufnahmeAktiv.value,
                "foregroundAktiv" to isForegroundActive,
                "meterState" to connectionSupervisor.state.value.name,
            ),
        )

        if (action == ACTION_STOP_SERVICE || action == "STOP_SERVICE") {
            expliziterServiceStopAngefordert = true
            diagnosticsReporter.breadcrumb(
                "AudioService",
                "Expliziter Messungs-/Service-Stop angefordert",
                data = mapOf(
                    "audioIstAktiv" to _audioAufnahmeAktiv.value,
                    "wavAktiv" to (activeWavRecorder != null),
                    "meterState" to connectionSupervisor.state.value.name,
                ),
            )
            settingsManager.monitoringWasActive = false
            settingsManager.audioMonitoringWasActive = false
            HeartbeatPlanung.stoppe(applicationContext)
            com.example.lrmprotokoll.drive.DriveSyncPlanung.stoppe(applicationContext)
            com.example.lrmprotokoll.diagnose.DiagnosticLogCleanupPlanung.stoppe(applicationContext)
            isRunning = false
            _audioAufnahmeAktiv.value = false
            _currentMicDb.value = null
            _laufendesFormat.value = null
            _laeuft.value = false
            triggerWachhund.zuruecksetzen()
            wavOhneMikrofonGemeldet = false
            audioSollIstFehlerGemeldet = false
            stillerAusfallHinweis = null
            NoiseMonitoringWidgetProvider.updateAlleWidgets(applicationContext)
            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_STOP_AUDIO_RECORDING || action == "STOP_AUDIO_RECORDING") {
            isRunning = false
            _audioAufnahmeAktiv.value = false
            _currentMicDb.value = null
            _laufendesFormat.value = null
            triggerWachhund.zuruecksetzen()
            wavOhneMikrofonGemeldet = false
            audioSollIstFehlerGemeldet = false
            stillerAusfallHinweis = null
            settingsManager.audioMonitoringWasActive = false
            diagnosticsReporter.breadcrumb(
                "AudioService",
                "Audio-Aufnahme explizit gestoppt (Hintergrund-Dienst bleibt aktiv)",
                data = mapOf("meterState" to connectionSupervisor.state.value.name),
            )
            updateNotification(connectionSupervisor.state.value)
            NoiseMonitoringWidgetProvider.updateAlleWidgets(applicationContext)
            return START_STICKY
        }

        expliziterServiceStopAngefordert = false
        if (!isForegroundActive) {
            isForegroundActive = true
            startForegroundService()
            settingsManager.monitoringWasActive = true
            _laeuft.value = true
        }

        val shouldStartAudio = sollAudioMonitoringStarten(
            expliziterStart = expliziterAudioStart,
            audioWarVorherAktiv = audioWarVorherAktiv,
        )

        if (!isRunning && shouldStartAudio) {
            isRunning = true
            // Erst AudioRecord.startRecording() darf den echten Live-Indikator auf aktiv setzen.
            _audioAufnahmeAktiv.value = false
            audioSollIstFehlerGemeldet = false
            settingsManager.audioMonitoringWasActive = true
            diagnosticsReporter.breadcrumb(
                "AudioService",
                if (expliziterAudioStart) "Audio-Monitoring wird explizit gestartet"
                else "Audio-Monitoring wird nach Service-Recreate wiederaufgenommen",
                data = mapOf(
                    "action" to (action ?: "<null>"),
                    "audioWarVorherAktiv" to audioWarVorherAktiv,
                ),
            )
            startMonitoring()
            measurementRecorder.starteMikrofonMessung()
        }

        ensureMeterMonitoringStarted()
        ensureDriveSyncStarted()
        ensureDiagnosticLoggingStarted()
        RetentionPlanung.plane(applicationContext)
        NoiseMonitoringWidgetProvider.updateAlleWidgets(applicationContext)
        return START_STICKY
    }

    private fun startForegroundService() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Lärm-Monitoring Dienst",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val hasBluetoothConnect = com.example.lrmprotokoll.meter.ble.BluetoothPermissions.hasConnectPermission(this)
        val hasRecordAudio = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        var serviceType = 0
        if (hasRecordAudio) serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (hasBluetoothConnect && settingsManager.meterDeviceAddress != null) {
            serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }

        try {
            if (serviceType != 0) {
                startForeground(NOTIFICATION_ID, buildNotification(connectionSupervisor.state.value), serviceType)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification(connectionSupervisor.state.value))
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
                startForeground(NOTIFICATION_ID, buildNotification(connectionSupervisor.state.value))
            } catch (fallbackEx: Throwable) {
                Log.e("AudioRecordingService", "Fallback startForeground fehlgeschlagen", fallbackEx)
                stopSelf()
            }
        }
    }

    private fun updateNotification(state: ConnectionState) {
        if (!isForegroundActive) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(meterState: ConnectionState): Notification {
        val stopIntent = Intent(this, AudioRecordingService::class.java).apply { action = ACTION_STOP_SERVICE }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        val contentText = stillerAusfallHinweis ?: leiteNotificationTextAb(
            istMessgeraetGepinnt = settingsManager.meterDeviceAddress != null,
            meterState = meterState,
            meterPegel = letzterMeterFrame?.level,
            mikrofonPegel = _currentMicDb.value,
        )
        val icon = if (stillerAusfallHinweis != null || istNotificationZustandGestoert(meterState)) {
            android.R.drawable.stat_sys_warning
        } else {
            android.R.drawable.ic_btn_speak_now
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Lärm-Monitoring aktiv")
            .setContentText(contentText)
            .setSmallIcon(icon)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stoppen", stopPendingIntent)
            .build()
    }

    private fun startMonitoring() {
        serviceScope.launch {
            if (ActivityCompat.checkSelfPermission(this@AudioRecordingService, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Log.e("AudioRecordingService", "Mikrofon-Berechtigung nicht erteilt - Monitoring wird nicht gestartet")
                diagnosticsReporter.report(
                    code = com.example.lrmprotokoll.diagnose.DiagnosticCode.PERMISSION_REVOKED_DURING_OPERATION,
                    component = "AudioRecordingService",
                    operation = "startMonitoring",
                    severity = com.example.lrmprotokoll.diagnose.DiagnosticSeverity.ERROR,
                    message = "RECORD_AUDIO fehlt beim Start der erwarteten Audioaufnahme",
                )
                isRunning = false
                _audioAufnahmeAktiv.value = false
                settingsManager.audioMonitoringWasActive = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }

            val audioManager = getSystemService(AudioManager::class.java)
            val unterstuetztUnprocessed = audioManager
                ?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
            val audioSource = waehleAufnahmequelle(unterstuetztUnprocessed)
            val gewuenschteRate = settingsManager.audioSampleRate
            val sampleRate = waehleAufnahmerate(gewuenschteRate) { kandidat ->
                AudioRecord.getMinBufferSize(kandidat, channelConfig, audioFormat) > 0
            }
            bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

            Log.i(
                "AudioRecordingService",
                "KI-Diagnose: audioSource=$audioSource (UNPROCESSED unterstuetzt=$unterstuetztUnprocessed), " +
                    "gewuenschteRate=$gewuenschteRate, tatsaechlicheRate=$sampleRate, " +
                    "channelConfig=$channelConfig, audioFormat=$audioFormat",
            )
            diagnosticsReporter.breadcrumb(
                "AudioService",
                "Mikrofon-Monitoring wird initialisiert (audioSource=$audioSource, sampleRate=$sampleRate)",
                data = mapOf(
                    "audioSource" to audioSource,
                    "unterstuetztUnprocessed" to unterstuetztUnprocessed,
                    "gewuenschteRate" to gewuenschteRate,
                    "tatsaechlicheRate" to sampleRate,
                    "channelConfig" to channelConfig,
                    "audioFormat" to audioFormat,
                ),
            )

            val audioRecord = try {
                AudioRecord(audioSource, sampleRate, channelConfig, audioFormat, bufferSize)
            } catch (e: Exception) {
                diagnosticsReporter.report(
                    code = com.example.lrmprotokoll.diagnose.DiagnosticCode.AUDIO_INIT_FAILED,
                    component = "AudioRecordingService",
                    operation = "startMonitoring.create",
                    severity = com.example.lrmprotokoll.diagnose.DiagnosticSeverity.ERROR,
                    cause = e,
                    details = mapOf("sampleRate" to sampleRate, "bufferSize" to bufferSize),
                )
                isRunning = false
                _audioAufnahmeAktiv.value = false
                settingsManager.audioMonitoringWasActive = false
                stopSelf()
                return@launch
            }

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                diagnosticsReporter.report(
                    code = com.example.lrmprotokoll.diagnose.DiagnosticCode.AUDIO_INIT_FAILED,
                    component = "AudioRecordingService",
                    operation = "startMonitoring.state",
                    severity = com.example.lrmprotokoll.diagnose.DiagnosticSeverity.ERROR,
                    message = "AudioRecord Initialisierung fehlgeschlagen (sampleRate=$sampleRate, bufferSize=$bufferSize)",
                    details = mapOf("sampleRate" to sampleRate, "bufferSize" to bufferSize)
                )
                isRunning = false
                _audioAufnahmeAktiv.value = false
                settingsManager.audioMonitoringWasActive = false
                audioRecord.release()
                stopSelf()
                return@launch
            }

            try {
                audioRecord.startRecording()
            } catch (e: Throwable) {
                diagnosticsReporter.report(
                    code = com.example.lrmprotokoll.diagnose.DiagnosticCode.AUDIO_INIT_FAILED,
                    component = "AudioRecordingService",
                    operation = "startMonitoring.startRecording",
                    severity = com.example.lrmprotokoll.diagnose.DiagnosticSeverity.ERROR,
                    cause = e,
                    details = mapOf("sampleRate" to sampleRate, "bufferSize" to bufferSize),
                )
                isRunning = false
                _audioAufnahmeAktiv.value = false
                settingsManager.audioMonitoringWasActive = false
                audioRecord.release()
                stopSelf()
                return@launch
            }

            aktiveAufnahmequelle = audioSource
            aktiveAbtastrate = audioRecord.sampleRate
            aktiveKanalzahl = if (channelConfig == AudioFormat.CHANNEL_IN_MONO) 1 else 2
            aktiveAgcAktiv = deaktiviereAudioEffekteUndMeldeAgcZustand(audioRecord.audioSessionId)
            _laufendesFormat.value = Aufnahmeformat(audioRecord.sampleRate, aktiveKanalzahl ?: 1)
            _audioAufnahmeAktiv.value = true
            audioSollIstFehlerGemeldet = false
            if (stillerAusfallHinweis == "WAV-/Mikrofon-Aufzeichnung unerwartet inaktiv") stillerAusfallHinweis = null
            diagnosticsReporter.breadcrumb(
                "AudioService",
                "Mikrofon-Monitoring aktiv",
                data = mapOf(
                    "sampleRate" to audioRecord.sampleRate,
                    "audioSessionId" to audioRecord.audioSessionId,
                    "kanalzahl" to (aktiveKanalzahl ?: 1),
                ),
            )
            NoiseMonitoringWidgetProvider.updateAlleWidgets(applicationContext)

            val buffer = ShortArray(bufferSize / 2)
            val tempByteBuffer = ByteBuffer.allocate(bufferSize).order(ByteOrder.LITTLE_ENDIAN)
            var unerwarteterReadFehler: Int? = null

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

                    val pcmBytes = tempByteBuffer.array()
                    val pcmLen = readSize * 2
                    updateRollingBuffer()
                    writeToRollingBuffer(pcmBytes, pcmLen)

                    activeWavRecorder?.let { rec ->
                        rec.writeChunk(pcmBytes, pcmLen)
                        if (maxAmplitude > rec.maxAmplitude) rec.maxAmplitude = maxAmplitude.toDouble()
                    }
                    videoTonMitschnitt.schreibe(pcmBytes, pcmLen, System.currentTimeMillis())

                    val currentDb = calculateDb(buffer, readSize)
                    letzterMikrofonDb = currentDb
                    _currentMicDb.value = currentDb
                    if (settingsManager.driveSyncEnabled) {
                        levelSampleCollector.pegel(LevelSource.MIKROFON, currentDb, Instant.now())
                    }
                    measurementRecorder.mikrofonPegel(currentDb)
                    pruefeSchwellenwertUndTrigger(
                        meterFrame = letzterMeterFrame,
                        mikrofonDb = currentDb,
                        maxAmplitude = maxAmplitude.toDouble()
                    )
                } else if (readSize < 0) {
                    unerwarteterReadFehler = readSize
                    diagnosticsReporter.report(
                        code = com.example.lrmprotokoll.diagnose.DiagnosticCode.AUDIO_READ_FAILED,
                        component = "AudioRecordingService",
                        operation = "AudioRecord.read",
                        severity = com.example.lrmprotokoll.diagnose.DiagnosticSeverity.ERROR,
                        message = "AudioRecord.read lieferte Fehlercode $readSize",
                        details = mapOf(
                            "readSize" to readSize,
                            "sampleRate" to audioRecord.sampleRate,
                            "meterState" to connectionSupervisor.state.value.name,
                        ),
                    )
                    isRunning = false
                }
            }

            _audioAufnahmeAktiv.value = false
            _currentMicDb.value = null
            _laufendesFormat.value = null
            diagnosticsReporter.breadcrumb(
                "AudioService",
                "Mikrofon-Monitoring beendet",
                data = mapOf(
                    "readFehler" to unerwarteterReadFehler,
                    "audioWeiterErwartet" to settingsManager.audioMonitoringWasActive,
                    "meterState" to connectionSupervisor.state.value.name,
                ),
            )
            try {
                if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) audioRecord.stop()
            } catch (_: Throwable) { }
            audioRecord.release()
            NoiseMonitoringWidgetProvider.updateAlleWidgets(applicationContext)

            // Ein echter Read-Fehler soll nicht zu einem "halb lebenden" Dienst fuehren. Durch
            // START_STICKY + AudioStartPolicy wird der zuvor aktive Audiopfad beim Recreate
            // wieder aufgenommen; das Diagnose-Bundle enthaelt gleichzeitig den exakten Grund.
            if (unerwarteterReadFehler != null && settingsManager.audioMonitoringWasActive) {
                stopSelf()
            }
        }
    }

    private fun deaktiviereAudioEffekteUndMeldeAgcZustand(audioSessionId: Int): Boolean? {
        val aecStatus = try {
            if (!AcousticEchoCanceler.isAvailable()) {
                "nicht_verfuegbar"
            } else {
                val aec = AcousticEchoCanceler.create(audioSessionId)
                if (aec == null) "nicht_erstellbar" else { aec.enabled = false; "deaktiviert" }
            }
        } catch (e: Throwable) {
            Log.w("AudioRecordingService", "AcousticEchoCanceler konnte nicht deaktiviert werden", e)
            "fehler"
        }
        val nsStatus = try {
            if (!NoiseSuppressor.isAvailable()) {
                "nicht_verfuegbar"
            } else {
                val ns = NoiseSuppressor.create(audioSessionId)
                if (ns == null) "nicht_erstellbar" else { ns.enabled = false; "deaktiviert" }
            }
        } catch (e: Throwable) {
            Log.w("AudioRecordingService", "NoiseSuppressor konnte nicht deaktiviert werden", e)
            "fehler"
        }
        val agcAktiv = try {
            if (!AutomaticGainControl.isAvailable()) null
            else AutomaticGainControl.create(audioSessionId)?.let { agc -> agc.enabled = false; false }
        } catch (e: Throwable) {
            Log.w("AudioRecordingService", "AutomaticGainControl konnte nicht deaktiviert werden", e)
            null
        }

        diagnosticsReporter.breadcrumb(
            "AudioService",
            "Audioeffekte geprueft (aec=$aecStatus, ns=$nsStatus, agcAktiv=$agcAktiv)",
            data = mapOf("aec" to aecStatus, "ns" to nsStatus, "agcAktiv" to agcAktiv),
        )
        return agcAktiv
    }

    private fun pruefeSchwellenwertUndTrigger(
        meterFrame: com.example.lrmprotokoll.meter.MeterFrame?,
        mikrofonDb: Double?,
        maxAmplitude: Double = 0.0,
    ) {
        val mikrofonLaeuft = isRunning && _audioAufnahmeAktiv.value
        val messgeraetStreamt = connectionSupervisor.state.value == ConnectionState.STREAMING
        if (!mikrofonLaeuft && !messgeraetStreamt) return
        if (isRecordingActive.get()) return
        if (System.currentTimeMillis() - letzteAufnahmeEndeTimestamp < 1000L) return

        val cal = java.util.Calendar.getInstance()
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val start = settingsManager.quietHoursStartHour
        val end = settingsManager.quietHoursEndHour
        val isQuiet = if (settingsManager.quietHoursEnabled) {
            if (start <= end) (hour >= start && hour < end) else (hour >= start || hour < end)
        } else false

        val activeSchwelle = if (isQuiet) settingsManager.quietHoursThreshold else settingsManager.dbThreshold
        val auswertung = com.example.lrmprotokoll.messreihe.MeterTriggerSource.auswerten(
            letzterMeterFrame = meterFrame ?: letzterMeterFrame,
            mikrofonDb = mikrofonDb ?: letzterMikrofonDb,
            activeSchwelle = activeSchwelle,
            triggerQuelle = settingsManager.audioTriggerQuelle,
        )

        triggerWachhund.pegelGesehen(System.currentTimeMillis(), auswertung.ausgeloest)
        if (!auswertung.ausgeloest) return

        val wavMoeglich = settingsManager.recordWavAudio && mikrofonLaeuft
        if (wavMoeglich) {
            if (isRecordingActive.compareAndSet(false, true)) {
                serviceScope.launch(Dispatchers.IO) {
                    starteWavAufnahme(maxAmplitude, mikrofonDb ?: auswertung.pegel, auswertung, isQuiet)
                }
            }
            return
        }

        if (settingsManager.recordWavAudio && !mikrofonLaeuft) meldeWavOhneMikrofon()
        if (isRecordingActive.compareAndSet(false, true)) {
            serviceScope.launch(Dispatchers.IO) {
                speicherePegelEreignisOhneAudio(auswertung, isQuiet, mikrofonDb)
            }
        }
    }

    private fun meldeWavOhneMikrofon() {
        if (wavOhneMikrofonGemeldet) return
        wavOhneMikrofonGemeldet = true
        diagnosticsReporter.breadcrumb(
            "AudioService",
            "Ereignis ohne Tonaufnahme gespeichert - Mikrofon-Ueberwachung ist aus",
            data = mapOf(
                "triggerQuelle" to settingsManager.audioTriggerQuelle,
                "recordWavAudio" to true,
                "mikrofonLaeuft" to false,
                "audioWarAlsAktivGespeichert" to settingsManager.audioMonitoringWasActive,
                "serviceAktiv" to _laeuft.value,
            ),
        )
        updateNotification(connectionSupervisor.state.value)
    }

    private suspend fun speicherePegelEreignisOhneAudio(
        auswertung: com.example.lrmprotokoll.messreihe.MeterTriggerSource.Auswertung,
        isQuiet: Boolean,
        mikrofonDb: Double?,
    ) {
        val timestamp = System.currentTimeMillis()
        try {
            val dao = (application as LaermprotokollApp).container.database.noiseDao()
            dao.insert(
                NoiseRecord(
                    timestamp = timestamp,
                    amplitude = 0.0,
                    dbValue = mikrofonDb ?: auswertung.pegel,
                    filePath = "",
                    detectedLabel = null,
                    calibratedDbA = auswertung.calibratedDbA,
                    meterWeighting = auswertung.meterWeighting,
                    meterConnected = auswertung.meterConnected,
                    isQuietHour = isQuiet,
                    aufnahmeQuelle = aktiveAufnahmequelle,
                    abtastrate = aktiveAbtastrate,
                    kanalzahl = aktiveKanalzahl,
                    agcAktiv = aktiveAgcAktiv,
                )
            )
            triggerWachhund.ereignisGespeichert(timestamp)
            stillerAusfallHinweis = null
            Log.i("AudioRecordingService", "Reines Pegelereignis gespeichert (DSGVO-Modus ohne Audio): ${auswertung.pegel} dB")
        } catch (e: Throwable) {
            Log.e("AudioRecordingService", "Fehler beim Speichern des NoiseRecord ohne Audio", e)
        } finally {
            letzteAufnahmeEndeTimestamp = System.currentTimeMillis()
            delay(2000L)
            isRecordingActive.set(false)
        }
    }

    /** Meldet die Soll/Ist-Abweichung auch dann, wenn gerade kein Pegel ueber der Schwelle liegt. */
    private fun pruefeAudioSollIstAbweichung() {
        val audioErwartet = settingsManager.audioMonitoringWasActive
        val audioIstAktiv = _audioAufnahmeAktiv.value
        if (!audioErwartet || audioIstAktiv) {
            audioSollIstFehlerGemeldet = false
            if (audioIstAktiv && stillerAusfallHinweis == "WAV-/Mikrofon-Aufzeichnung unerwartet inaktiv") {
                stillerAusfallHinweis = null
            }
            return
        }
        if (audioSollIstFehlerGemeldet) return
        audioSollIstFehlerGemeldet = true
        stillerAusfallHinweis = "WAV-/Mikrofon-Aufzeichnung unerwartet inaktiv"
        diagnosticsReporter.report(
            code = com.example.lrmprotokoll.diagnose.DiagnosticCode.AUDIO_MONITORING_STOPPED_UNEXPECTEDLY,
            component = "AudioRecordingService",
            operation = "pruefeAudioSollIstAbweichung",
            severity = com.example.lrmprotokoll.diagnose.DiagnosticSeverity.ERROR,
            message = stillerAusfallHinweis ?: "",
            details = mapOf(
                "audioMonitoringWasActive" to settingsManager.audioMonitoringWasActive,
                "isRunning" to isRunning,
                "audioAufnahmeAktiv" to _audioAufnahmeAktiv.value,
                "recordWavAudio" to settingsManager.recordWavAudio,
                "foregroundAktiv" to isForegroundActive,
                "meterState" to connectionSupervisor.state.value.name,
                "aktiveWavDatei" to activeWavRecorder?.file?.name,
            ),
        )
    }

    private fun pruefeStillenAusfall() {
        val jetzt = System.currentTimeMillis()
        if (!triggerWachhund.stillerAusfall(jetzt)) return

        val minuten = triggerWachhund.dauerUeberSchwelleMs(jetzt) / 60_000L
        val grund = if (!_audioAufnahmeAktiv.value && settingsManager.recordWavAudio) {
            "Mikrofon-Überwachung ist aus"
        } else {
            "Ursache unklar"
        }
        stillerAusfallHinweis = "Seit $minuten Min. über der Schwelle, aber keine Ereignisse – $grund"
        diagnosticsReporter.report(
            code = com.example.lrmprotokoll.diagnose.DiagnosticCode.TRIGGER_STILLER_AUSFALL,
            component = "AudioRecordingService",
            operation = "pruefeStillenAusfall",
            severity = com.example.lrmprotokoll.diagnose.DiagnosticSeverity.ERROR,
            message = stillerAusfallHinweis ?: "",
            details = mapOf(
                "minutenUeberSchwelle" to minuten,
                "mikrofonSollLaufen" to isRunning,
                "mikrofonIstAktiv" to _audioAufnahmeAktiv.value,
                "triggerQuelle" to settingsManager.audioTriggerQuelle,
                "recordWavAudio" to settingsManager.recordWavAudio,
                "verbindung" to connectionSupervisor.state.value.name,
            ),
        )
        updateNotification(connectionSupervisor.state.value)
    }

    private fun calculateDb(buffer: ShortArray, readSize: Int): Double {
        if (readSize <= 0) return 0.0
        var sum = 0.0
        var maxAmp = 0
        for (i in 0 until readSize) {
            val sample = buffer[i].toDouble()
            sum += sample * sample
            val abs = Math.abs(buffer[i].toInt())
            if (abs > maxAmp) maxAmp = abs
        }
        val rms = Math.sqrt(sum / readSize)
        val rmsDb = if (rms > 0) 20 * Math.log10(rms / 32767.0) + 100.0 else 0.0
        val peakDb = if (maxAmp > 0) 20 * Math.log10(maxAmp / 32767.0) + 100.0 else 0.0
        val db = Math.max(rmsDb, peakDb - 6.0)
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

    private var wavEventCounter = 1
    private val isRecordingActive = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var letzteAufnahmeEndeTimestamp: Long = 0L
    @Volatile private var letzterMikrofonDb: Double = 0.0

    private class ActiveWavRecorder(
        val file: File,
        val outputStream: FileOutputStream,
        val durationMs: Long,
        val sampleRate: Int,
        val auswertung: com.example.lrmprotokoll.messreihe.MeterTriggerSource.Auswertung,
        val isQuiet: Boolean,
        val timestamp: Long,
        var maxAmplitude: Double = 0.0,
        var totalDataLen: Long = 0L,
        val startTime: Long = System.currentTimeMillis(),
    ) {
        fun writeChunk(data: ByteArray, size: Int) {
            outputStream.write(data, 0, size)
            totalDataLen += size
        }
    }

    @Volatile private var activeWavRecorder: ActiveWavRecorder? = null
    private val triggerWachhund = TriggerWachhund()
    @Volatile private var wavOhneMikrofonGemeldet = false
    @Volatile private var stillerAusfallHinweis: String? = null

    private suspend fun starteWavAufnahme(
        initialAmplitude: Double,
        dbValue: Double,
        auswertung: com.example.lrmprotokoll.messreihe.MeterTriggerSource.Auswertung,
        isQuiet: Boolean,
    ) {
        val timestamp = System.currentTimeMillis()
        val dateStr = java.text.SimpleDateFormat("yyyyMMdd_HH_mm_ss", java.util.Locale.US).format(java.util.Date(timestamp))
        val fileName = "${dateStr}_${wavEventCounter++}.wav"
        val file = File(getExternalFilesDir(null), fileName)
        val sampleRate = aktiveAbtastrate ?: settingsManager.audioSampleRate
        val durationMs = settingsManager.recordDurationSeconds * 1000L

        var totalDataLen = 0L
        val fos = try {
            val stream = FileOutputStream(file)
            writeWavHeader(stream, channelConfig, sampleRate, audioFormat, 0)
            val preRoll = getPreRollData()
            stream.write(preRoll)
            totalDataLen = preRoll.size.toLong()
            stream
        } catch (e: Throwable) {
            diagnosticsReporter.report(
                code = com.example.lrmprotokoll.diagnose.DiagnosticCode.AUDIO_FILE_WRITE_FAILED,
                component = "AudioRecordingService",
                operation = "starteWavAufnahme",
                severity = com.example.lrmprotokoll.diagnose.DiagnosticSeverity.ERROR,
                cause = e,
                details = mapOf("fileName" to fileName, "path" to file.absolutePath),
            )
            isRecordingActive.set(false)
            return
        }

        val recorder = ActiveWavRecorder(
            file = file,
            outputStream = fos,
            durationMs = durationMs,
            sampleRate = sampleRate,
            auswertung = auswertung,
            isQuiet = isQuiet,
            timestamp = timestamp,
            maxAmplitude = initialAmplitude,
            totalDataLen = totalDataLen,
        )
        activeWavRecorder = recorder
        diagnosticsReporter.breadcrumb(
            "AudioService",
            "WAV-Aufnahme gestartet",
            data = mapOf(
                "fileName" to fileName,
                "path" to file.absolutePath,
                "sampleRate" to sampleRate,
                "zielDauerMs" to durationMs,
                "preRollBytes" to totalDataLen,
                "meterConnected" to auswertung.meterConnected,
                "pegelDb" to auswertung.pegel,
            ),
        )

        val startWait = System.currentTimeMillis()
        while (System.currentTimeMillis() - startWait < durationMs && isRunning && _audioAufnahmeAktiv.value) {
            delay(50)
        }
        val actualDurationMs = System.currentTimeMillis() - startWait
        val interrupted = actualDurationMs + 150L < durationMs

        activeWavRecorder = null
        try {
            fos.close()
            updateWavHeader(file, recorder.totalDataLen)
        } catch (e: Throwable) {
            diagnosticsReporter.report(
                code = com.example.lrmprotokoll.diagnose.DiagnosticCode.AUDIO_FILE_WRITE_FAILED,
                component = "AudioRecordingService",
                operation = "finalisiereWavAufnahme",
                severity = com.example.lrmprotokoll.diagnose.DiagnosticSeverity.ERROR,
                cause = e,
                details = mapOf("fileName" to fileName, "dataBytes" to recorder.totalDataLen),
            )
        }

        diagnosticsReporter.breadcrumb(
            "AudioService",
            "WAV-Aufnahme beendet",
            data = mapOf(
                "fileName" to fileName,
                "dauerMs" to actualDurationMs,
                "zielDauerMs" to durationMs,
                "dataBytes" to recorder.totalDataLen,
                "fileBytes" to file.length(),
                "unterbrochen" to interrupted,
                "audioIstAktiv" to _audioAufnahmeAktiv.value,
            ),
        )
        if (interrupted) {
            diagnosticsReporter.report(
                code = com.example.lrmprotokoll.diagnose.DiagnosticCode.AUDIO_WAV_INTERRUPTED,
                component = "AudioRecordingService",
                operation = "starteWavAufnahme",
                severity = com.example.lrmprotokoll.diagnose.DiagnosticSeverity.WARN,
                message = "WAV-Aufnahme endete vor der konfigurierten Dauer",
                details = mapOf(
                    "fileName" to fileName,
                    "dauerMs" to actualDurationMs,
                    "zielDauerMs" to durationMs,
                    "audioMonitoringWasActive" to settingsManager.audioMonitoringWasActive,
                    "audioAufnahmeAktiv" to _audioAufnahmeAktiv.value,
                ),
            )
        }

        val shouldClassifyOnline = settingsManager.aiMode == "ONLINE"
        val ergebnis = if (shouldClassifyOnline) {
            try {
                classifier?.klassifiziereMitRohdaten(file)
            } catch (e: Throwable) {
                Log.w("AudioRecordingService", "Klassifikation fehlgeschlagen, Aufnahme bleibt erhalten", e)
                null
            }
        } else null
        val detected = ergebnis?.label

        try {
            val database = (application as LaermprotokollApp).container.database
            triggerWachhund.ereignisGespeichert(timestamp)
            stillerAusfallHinweis = null
            val neueId = database.noiseDao().insert(
                NoiseRecord(
                    timestamp = timestamp,
                    amplitude = recorder.maxAmplitude,
                    dbValue = dbValue,
                    filePath = file.absolutePath,
                    detectedLabel = detected,
                    calibratedDbA = auswertung.calibratedDbA,
                    meterWeighting = auswertung.meterWeighting,
                    meterConnected = auswertung.meterConnected,
                    isQuietHour = isQuiet,
                    aufnahmeQuelle = aktiveAufnahmequelle,
                    abtastrate = aktiveAbtastrate,
                    kanalzahl = aktiveKanalzahl,
                    agcAktiv = aktiveAgcAktiv,
                ),
            )
            var rohdatenGespeichert = false
            if (ergebnis != null) {
                try {
                    database.klassifikationsRohdatenDao().insert(ergebnis.rohdaten.mitRecordId(neueId))
                    rohdatenGespeichert = true
                } catch (e: Throwable) {
                    diagnosticsReporter.report(
                        code = com.example.lrmprotokoll.diagnose.DiagnosticCode.DB_WRITE_FAILED,
                        component = "AudioRecordingService",
                        operation = "starteWavAufnahme.rohdaten",
                        severity = com.example.lrmprotokoll.diagnose.DiagnosticSeverity.WARN,
                        cause = e,
                        message = "Klassifikations-Rohdaten konnten nicht gespeichert werden",
                    )
                }
            }
            diagnosticsReporter.breadcrumb(
                "AudioService",
                "NoiseRecord gespeichert (KI=$detected, Rohdaten=$rohdatenGespeichert)",
                data = mapOf(
                    "recordId" to neueId,
                    "fileName" to fileName,
                    "fileBytes" to file.length(),
                    "detectedLabel" to detected,
                    "rohdatenGespeichert" to rohdatenGespeichert,
                    "aufnahmeQuelle" to aktiveAufnahmequelle,
                    "abtastrate" to aktiveAbtastrate,
                    "kanalzahl" to aktiveKanalzahl,
                    "agcAktiv" to aktiveAgcAktiv,
                ),
            )
        } catch (e: Throwable) {
            Log.e("AudioRecordingService", "Fehler beim Speichern des NoiseRecord in DB", e)
            diagnosticsReporter.report(
                code = com.example.lrmprotokoll.diagnose.DiagnosticCode.DB_WRITE_FAILED,
                component = "AudioRecordingService",
                operation = "starteWavAufnahme.noiseRecord",
                severity = com.example.lrmprotokoll.diagnose.DiagnosticSeverity.ERROR,
                cause = e,
                details = mapOf("fileName" to fileName, "fileBytes" to file.length()),
            )
        }

        if (settingsManager.driveSyncEnabled && settingsManager.driveUploadWav) {
            com.example.lrmprotokoll.drive.DriveSyncPlanung.starteSofort(applicationContext)
        }

        letzteAufnahmeEndeTimestamp = System.currentTimeMillis()
        isRecordingActive.set(false)
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
            putInt(16)
            putShort(1.toShort())
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate.toInt())
            putShort((channels * bitsPerSample / 8).toShort())
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
        val audioWarErwartet = settingsManager.audioMonitoringWasActive
        val wav = activeWavRecorder
        diagnosticsReporter.breadcrumb(
            "AudioService",
            "AudioRecordingService wird beendet",
            data = mapOf(
                "expliziterServiceStop" to expliziterServiceStopAngefordert,
                "audioMonitoringWasActive" to audioWarErwartet,
                "audioAufnahmeAktiv" to _audioAufnahmeAktiv.value,
                "isRunning" to isRunning,
                "aktiveWavDatei" to wav?.file?.name,
                "aktiveWavBytes" to wav?.totalDataLen,
                "meterState" to connectionSupervisor.state.value.name,
            ),
        )
        if (!expliziterServiceStopAngefordert && audioWarErwartet) {
            diagnosticsReporter.report(
                code = com.example.lrmprotokoll.diagnose.DiagnosticCode.AUDIO_SERVICE_DESTROYED_WHILE_RECORDING,
                component = "AudioRecordingService",
                operation = "onDestroy",
                severity = com.example.lrmprotokoll.diagnose.DiagnosticSeverity.WARN,
                message = "Service wurde beendet, obwohl Audio weiterhin als aktiv markiert war; START_STICKY soll die Aufnahme wiederherstellen.",
                details = mapOf(
                    "audioAufnahmeAktiv" to _audioAufnahmeAktiv.value,
                    "aktiveWavDatei" to wav?.file?.name,
                    "aktiveWavBytes" to wav?.totalDataLen,
                    "meterState" to connectionSupervisor.state.value.name,
                ),
            )
        }
        if (wav != null && !expliziterServiceStopAngefordert) {
            diagnosticsReporter.report(
                code = com.example.lrmprotokoll.diagnose.DiagnosticCode.AUDIO_WAV_INTERRUPTED,
                component = "AudioRecordingService",
                operation = "onDestroy",
                severity = com.example.lrmprotokoll.diagnose.DiagnosticSeverity.WARN,
                message = "Service-Ende waehrend einer aktiven WAV-Aufnahme",
                details = mapOf(
                    "fileName" to wav.file.name,
                    "dataBytes" to wav.totalDataLen,
                    "laufzeitMs" to (System.currentTimeMillis() - wav.startTime),
                    "zielDauerMs" to wav.durationMs,
                ),
            )
        }

        isRunning = false
        _audioAufnahmeAktiv.value = false
        _currentMicDb.value = null
        _laufendesFormat.value = null
        _laeuft.value = false
        NoiseMonitoringWidgetProvider.updateAlleWidgets(applicationContext)
        connectionSupervisor.stop()
        alarmCoordinator.stop()
        levelSampleCollector.stop()
        measurementRecorder.stop()
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)
}
