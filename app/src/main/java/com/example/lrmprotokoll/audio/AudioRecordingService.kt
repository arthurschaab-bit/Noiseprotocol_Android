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
 * Steuert, ob dieser Start auch die Mikrofon-Ueberwachung anstossen soll. Fehlt das Extra
 * (z. B. wenn MeterScreen den Dienst nur startet, damit ein frisch gepinntes Geraet unter dem
 * Schutz des Foreground Service verbunden wird), bleibt die Aufnahme unberuehrt - das Koppeln
 * eines Messgeraets soll nicht ungefragt die Mikrofon-Schwellwertueberwachung mitstarten.
 */
const val EXTRA_START_AUDIO_MONITORING = "start_audio_monitoring"
const val ACTION_START_AUDIO_MONITORING = "com.example.lrmprotokoll.START_AUDIO_MONITORING"
const val ACTION_STOP_AUDIO_RECORDING = "com.example.lrmprotokoll.STOP_AUDIO_RECORDING"
const val ACTION_STOP_SERVICE = "STOP_SERVICE"

class AudioRecordingService : LifecycleService() {

    companion object {
        // M7c Aufgabe 1: Live-Status-Dashboard braucht einen echten, beobachtbaren "laeuft
        // der Dienst"-Zustand statt des bisherigen einmaligen ActivityManager.getRunningServices()
        // -Polls (Bestandsaufnahme-Befund 4.1/Verbesserungsvorschlag 1) - true, sobald der
        // Foreground-Zustand steht (deckt sowohl reine Mikrofon- als auch Messgeraet-Ueberwachung
        // ab), false nach explizitem Stop oder onDestroy.
        private val _laeuft = MutableStateFlow(false)
        val laeuft: StateFlow<Boolean> = _laeuft.asStateFlow()

        // Zeigt an, ob die Mikrofon-Audioaufnahme / Schwellwert-Überwachung aktiv aufzeichnet.
        private val _audioAufnahmeAktiv = MutableStateFlow(false)
        val audioAufnahmeAktiv: StateFlow<Boolean> = _audioAufnahmeAktiv.asStateFlow()

        // PROMPT_M10_FUNKTIONEN.md F1 (Schwellenwert-Assistent): der zuletzt berechnete
        // Mikrofonpegel, damit SettingsScreen ihn live neben dem Schwellen-Slider zeigen kann,
        // ohne selbst eine Aufnahme zu starten. `null`, solange keine Überwachung läuft - ein
        // eingefrorener Restwert aus einer frueheren Session waere irrefuehrend (dasselbe
        // Prinzip wie [com.example.lrmprotokoll.messreihe.leiteDashboardAnzeigeAb]).
        private val _currentMicDb = MutableStateFlow<Double?>(null)
        val currentMicDb: StateFlow<Double?> = _currentMicDb.asStateFlow()

        /**
         * Das vom `AudioRecord` **tatsaechlich ausgehandelte** Format der laufenden Aufnahme,
         * `null` wenn keine laeuft (M11 Etappe B).
         *
         * Der Videobeweis braucht genau diese Werte, um den mitgeschnittenen Ton spaeter
         * einmultiplexen zu koennen. Die eingestellte Rate genuegt dafuer nicht: Weicht die
         * ausgehandelte davon ab (siehe `waehleAufnahmerate()`), waere der Ton im fertigen Video
         * in falscher Tonhoehe und falscher Laenge.
         */
        private val _laufendesFormat = MutableStateFlow<Aufnahmeformat?>(null)
        val laufendesFormat: StateFlow<Aufnahmeformat?> = _laufendesFormat.asStateFlow()

        /** Nur fuer Compose-UI-Tests, die den Servicestart nicht real ausloesen koennen (kein
         * Mikrofon unter Robolectric) - internal statt private, damit das Testmodul zugreifen
         * kann, ohne den Setter Teil der eigentlichen Produktions-API zu machen. */
        internal fun testSetzeLaeuft(wert: Boolean) { _laeuft.value = wert }
        internal fun testSetzeAudioAufnahmeAktiv(wert: Boolean) { _audioAufnahmeAktiv.value = wert }
        internal fun testSetzeCurrentMicDb(wert: Double?) { _currentMicDb.value = wert }
        internal fun testSetzeLaufendesFormat(wert: Aufnahmeformat?) { _laufendesFormat.value = wert }
    }

    /** Abtastrate und Kanalzahl einer laufenden Mikrofonaufnahme. */
    data class Aufnahmeformat(val abtastrate: Int, val kanaele: Int)

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var isForegroundActive = false
    private var isRunning = false
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bytesPerSample = 2
    private var bufferSize = 0

    // KI-Umbau Etappe 1.2/1.3: die tatsaechlichen Aufnahmebedingungen der laufenden Mikrofon-
    // Ueberwachung - koennen von den Einstellungen abweichen, wenn das Geraet die gewuenschte
    // Abtastrate nicht unterstuetzt (siehe waehleAufnahmerate()). @Volatile, weil starteWavAufnahme
    // aus einer anderen Coroutine als startMonitoring() liest.
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
    // KI-Umbau Etappe 1.4: RohdatenClassifier statt SoundClassifier - liefert zusaetzlich die
    // Rohdaten fuer die Persistenz. NoiseClassifier implementiert beide Interfaces.
    private var classifier: RohdatenClassifier? = null

    private var rollingBuffer: ByteArray = ByteArray(0)
    private var writeHead = 0
    private var isBufferFull = false

    private lateinit var measurementRecorder: com.example.lrmprotokoll.messreihe.MeasurementRecorder

    /**
     * Tonmitschnitt fuer ein laufendes Beweisvideo (M11 Etappe B). Die Kamera nimmt bewusst ohne
     * Tonspur auf; der Ton kommt aus dieser Schleife und wird nachtraeglich einmultiplext.
     */
    private lateinit var videoTonMitschnitt: com.example.lrmprotokoll.video.VideoTonMitschnitt

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
        videoTonMitschnitt = container.videoTonMitschnitt

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
                // Ein Frame gilt nur als "aktuell", solange die Verbindung wirklich steht - sonst
                // wuerde der letzte Wert vor einem Abbruch beliebig lange als Live-Pegel
                // durchgereicht, obwohl laengst nichts mehr ankommt.
                if (state != ConnectionState.STREAMING) letzterMeterFrame = null
            }
        }

        // PROMPT_M10_FUNKTIONEN.md F4: der aktuelle Pegel soll in der Notification stehen, aber
        // NICHT bei jedem Audio-Buffer (~515 ms) neu gesetzt werden - das kostet spuerbar Akku
        // und das System drosselt haeufige Notification-Updates ohnehin. Alle 5 Sekunden reicht,
        // um "live" zu wirken, ohne die Update-Rate von connectionSupervisor.state (nur bei
        // echten Zustandswechseln) zu erhoehen.
        lifecycleScope.launch {
            while (isActive) {
                delay(5000L)
                if (isForegroundActive) {
                    pruefeStillenAusfall()
                    updateNotification(connectionSupervisor.state.value)
                    // F14: dieselbe 5-Sekunden-Kadenz wie die Notification, keine eigene
                    // Alarm-/Timer-Infrastruktur fuer das Homescreen-Widget.
                    NoiseMonitoringWidgetProvider.updateAlleWidgets(applicationContext)
                }
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
        val hasBluetoothConnect = com.example.lrmprotokoll.meter.ble.BluetoothPermissions.hasConnectPermission(this)
        if (!hasBluetoothConnect) {
            Log.w("AudioRecordingService", "Bluetooth Berechtigung fehlt - Meter-Monitoring wird übersprungen")
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
        // Nach dem Start der Ueberwachung mit der TATSAECHLICH ausgehandelten Rate rechnen
        // (kann von der Einstellung abweichen, siehe waehleAufnahmerate()), davor mit der
        // gewuenschten Einstellung - es existiert noch kein AudioRecord, dessen echte Rate man
        // kennen koennte.
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
        if (intent?.action == ACTION_STOP_SERVICE || intent?.action == "STOP_SERVICE") {
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
            isRunning = false
            _audioAufnahmeAktiv.value = false
            _currentMicDb.value = null
            _laeuft.value = false
            triggerWachhund.zuruecksetzen()
            wavOhneMikrofonGemeldet = false
            stillerAusfallHinweis = null
            NoiseMonitoringWidgetProvider.updateAlleWidgets(applicationContext)
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_STOP_AUDIO_RECORDING || intent?.action == "STOP_AUDIO_RECORDING") {
            // Stoppt nur die Audioaufnahme/Schwellenwert-Überwachung, Foreground-Service und BLE-Verbindung bleiben aktiv
            isRunning = false
            _audioAufnahmeAktiv.value = false
            _currentMicDb.value = null
            triggerWachhund.zuruecksetzen()
            wavOhneMikrofonGemeldet = false
            stillerAusfallHinweis = null
            settingsManager.audioMonitoringWasActive = false
            diagnosticsReporter.breadcrumb("AudioService", "Audio-Aufnahme gestoppt (Hintergrund-Dienst bleibt aktiv)")
            updateNotification(connectionSupervisor.state.value)
            NoiseMonitoringWidgetProvider.updateAlleWidgets(applicationContext)
            return START_STICKY
        }

        if (!isForegroundActive) {
            isForegroundActive = true
            startForegroundService()
            settingsManager.monitoringWasActive = true
            _laeuft.value = true
        }

        val shouldStartAudio = intent?.getBooleanExtra(EXTRA_START_AUDIO_MONITORING, false) == true ||
            intent?.action == ACTION_START_AUDIO_MONITORING ||
            intent?.action == "START_AUDIO_MONITORING"

        if (!isRunning && shouldStartAudio) {
            isRunning = true
            _audioAufnahmeAktiv.value = true
            startMonitoring()
            settingsManager.audioMonitoringWasActive = true
            // M11/E1: Auch ein reiner Mikrofonlauf ist ein Messvorgang und bekommt eine Session -
            // sonst hat die Fotodokumentation nichts, woran sie haengen koennte. No-Op, wenn
            // bereits eine Session laeuft (etwa weil das Messgeraet schon verbunden ist).
            measurementRecorder.starteMikrofonMessung()
        }
        ensureMeterMonitoringStarted()
        ensureDriveSyncStarted()
        ensureDiagnosticLoggingStarted()
        // M4: unabhaengig von jeder Einstellung geplant (anders als Heartbeat/Drive-Sync) -
        // alte Rohwerte sollen verdichtet werden, sobald der Dienst ueberhaupt einmal laeuft,
        // unabhaengig davon, ob gerade ein Messgeraet gepinnt oder Drive-Sync aktiv ist.
        com.example.lrmprotokoll.messreihe.RetentionPlanung.plane(applicationContext)
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

        // Ein erkannter stiller Ausfall verdraengt den normalen Statustext: Er ist die
        // wichtigere Information, und die Notification ist der einzige Ort, an dem der Nutzer
        // ihn zuverlaessig sieht, ohne die App zu oeffnen.
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
                isRunning = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }

            // KI-Umbau Etappe 1.2: UNPROCESSED bevorzugt (kein AGC, keine Rauschunterdrueckung,
            // kein Hochpassfilter - siehe waehleAufnahmequelle()-KDoc), sonst MIC. Bewusst nie
            // VOICE_RECOGNITION/VOICE_COMMUNICATION/CAMCORDER.
            val audioManager = getSystemService(AudioManager::class.java)
            val unterstuetztUnprocessed = audioManager
                ?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
            val audioSource = waehleAufnahmequelle(unterstuetztUnprocessed)

            // Gewuenschte Rate aus den Einstellungen, aber niemals eine niedrigere als die
            // gewaehlte, falls das Geraet sie nicht unterstuetzt (siehe waehleAufnahmerate()).
            val gewuenschteRate = settingsManager.audioSampleRate
            val sampleRate = waehleAufnahmerate(gewuenschteRate) { kandidat ->
                AudioRecord.getMinBufferSize(kandidat, channelConfig, audioFormat) > 0
            }
            bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

            // KI-Umbau Etappe 1.1 (Diagnose, siehe Auftrag Abschnitt 1.1): diese Werte sind das
            // wichtigste Ergebnis der Etappe - auf einem echten Geraet mit Logcat pruefen, ob sie
            // den Erwartungen entsprechen (insbesondere: bleibt sampleRate == gewuenschteRate?).
            Log.i(
                "AudioRecordingService",
                "KI-Diagnose: audioSource=$audioSource (UNPROCESSED unterstuetzt=$unterstuetztUnprocessed), " +
                    "gewuenschteRate=$gewuenschteRate, tatsaechlicheRate=$sampleRate, " +
                    "channelConfig=$channelConfig, audioFormat=$audioFormat",
            )
            // Nachtrag zu Etappe 1.1: dieselben Werte zusaetzlich als Breadcrumb, damit sie im
            // Support-Bundle-Export landen - Logcat ist ohne angeschlossenen Rechner nicht
            // einsehbar, der Support-Bundle-Export dagegen schon.
            diagnosticsReporter.breadcrumb(
                "AudioService",
                "Mikrofon-Monitoring gestartet (audioSource=$audioSource, sampleRate=$sampleRate)",
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
                AudioRecord(
                    audioSource,
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

            // KI-Umbau Etappe 1.2/1.3: Aufnahmebedingungen fuer die Beweisdokumentation
            // festhalten - erst NACH startRecording(), weil erst dann audioSessionId belegt ist.
            aktiveAufnahmequelle = audioSource
            aktiveAbtastrate = audioRecord.sampleRate
            aktiveKanalzahl = if (channelConfig == AudioFormat.CHANNEL_IN_MONO) 1 else 2
            aktiveAgcAktiv = deaktiviereAudioEffekteUndMeldeAgcZustand(audioRecord.audioSessionId)
            _laufendesFormat.value = Aufnahmeformat(audioRecord.sampleRate, aktiveKanalzahl ?: 1)

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

                    val pcmBytes = tempByteBuffer.array()
                    val pcmLen = readSize * 2

                    updateRollingBuffer()
                    writeToRollingBuffer(pcmBytes, pcmLen)

                    activeWavRecorder?.let { rec ->
                        rec.writeChunk(pcmBytes, pcmLen)
                        if (maxAmplitude > rec.maxAmplitude) rec.maxAmplitude = maxAmplitude.toDouble()
                    }

                    // M11 Etappe B: Ton fuer ein laufendes Beweisvideo - eine Zeile NEBEN dem
                    // Ereignis-Mitschnitt, nicht statt dessen. Die WAV-Aufzeichnung oben ist
                    // ereignisgebunden und deckt einen Videozeitraum nicht ab. No-Op, solange
                    // kein Video laeuft; ein Schreibfehler beendet nur den Mitschnitt und darf
                    // diese Schleife nie verlassen.
                    videoTonMitschnitt.schreibe(pcmBytes, pcmLen, System.currentTimeMillis())

                    val currentDb = calculateDb(buffer, readSize)
                    letzterMikrofonDb = currentDb
                    _currentMicDb.value = currentDb

                    // M7b-Nachtrag: dieser Aufruf fehlte bislang komplett - der Mikrofon-Pfad hat
                    // nie in den Drive-Sync-Puffer geschrieben, obwohl der PCE-323-Pfad das schon
                    // seit M7b tat. Ohne Messgeraet blieb der Sync damit lautlos leer, obwohl er
                    // laut Plan genau dafuer auch allein mit Mikrofonwerten laufen sollte.
                    if (settingsManager.driveSyncEnabled) {
                        levelSampleCollector.pegel(LevelSource.MIKROFON, currentDb, Instant.now())
                    }

                    // Messreihe der laufenden Mikrofon-Session (M11/E1): No-Op, solange keine
                    // Mikrofon-Session laeuft - also insbesondere waehrend einer
                    // Messgeraet-Session, deren kalibrierte Werte nicht mit unkalibrierten
                    // Mikrofonwerten vermischt werden duerfen. Die Ausduennung auf einen Wert je
                    // Sekunde macht der Recorder selbst, nicht diese Schleife.
                    measurementRecorder.mikrofonPegel(currentDb)

                    pruefeSchwellenwertUndTrigger(
                        meterFrame = letzterMeterFrame,
                        mikrofonDb = currentDb,
                        maxAmplitude = maxAmplitude.toDouble()
                    )
                }
            }
            _laufendesFormat.value = null
            audioRecord.stop()
            audioRecord.release()
        }
    }

    /**
     * KI-Umbau Etappe 1.2: schaltet die drei Plattform-Audioeffekte ab, die genau die
     * Pegeldynamik verfaelschen wuerden, die Impulslaerm erkennbar macht - AGC normalisiert
     * Lautstaerkeunterschiede weg, die Rauschunterdrueckung ist darauf trainiert, stationaere
     * Geraeusche zu ENTFERNEN. Jeder Effekt einzeln try/catch-abgesichert: `create()`/
     * `isAvailable()` sind pro Geraet/Hersteller unterschiedlich zuverlaessig implementiert,
     * ein Fehler hier darf die laufende Ueberwachung nicht gefaehrden (Robustheitsgebot).
     *
     * @return `false`, wenn AGC verfuegbar war und erfolgreich abgeschaltet wurde; `null`, wenn
     * sich der Zustand nicht zuverlaessig feststellen liess (Effekt nicht verfuegbar oder
     * Fehler) - eine ehrliche "unbekannt"-Aussage statt eines erratenen `false`.
     */
    private fun deaktiviereAudioEffekteUndMeldeAgcZustand(audioSessionId: Int): Boolean? {
        val aecStatus = try {
            if (!AcousticEchoCanceler.isAvailable()) {
                "nicht_verfuegbar"
            } else {
                val aec = AcousticEchoCanceler.create(audioSessionId)
                if (aec == null) {
                    "nicht_erstellbar"
                } else {
                    aec.enabled = false
                    "deaktiviert"
                }
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
                if (ns == null) {
                    "nicht_erstellbar"
                } else {
                    ns.enabled = false
                    "deaktiviert"
                }
            }
        } catch (e: Throwable) {
            Log.w("AudioRecordingService", "NoiseSuppressor konnte nicht deaktiviert werden", e)
            "fehler"
        }
        val agcAktiv = try {
            if (!AutomaticGainControl.isAvailable()) {
                null
            } else {
                val agc = AutomaticGainControl.create(audioSessionId)
                if (agc == null) {
                    null
                } else {
                    agc.enabled = false
                    false
                }
            }
        } catch (e: Throwable) {
            Log.w("AudioRecordingService", "AutomaticGainControl konnte nicht deaktiviert werden", e)
            null
        }

        // Nachtrag zu Etappe 1.2: bislang wurde nur AGC in der DB persistiert (agcAktiv-Spalte),
        // der Erfolg/Misserfolg von AEC/NS blieb nur im (fluechtigen) Logcat sichtbar. Als
        // Breadcrumb landet der vollstaendige Status jetzt im Support-Bundle-Export.
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
        // Frueher stand hier "if (!isRunning) return". isRunning ist aber ausschliesslich das
        // Flag der MIKROFON-Ueberwachung (gesetzt nur ueber ACTION_START_AUDIO_MONITORING).
        // Der PCE-323-Frame-Collector ruft diese Methode zwar bei jedem Frame auf - jeder
        // Aufruf fiel jedoch hier heraus, solange die Mikrofon-Ueberwachung nicht separat lief.
        // Ergebnis beim Owner: 12 Stunden durchgehend ueber der Schwelle, null Ereignisse, bei
        // gleichzeitig weiterlaufender Session, Messreihe und Drive-Sync.
        //
        // Gemeint war "laeuft die Ueberwachung ueberhaupt" - und das ist der Fall, sobald
        // ENTWEDER die Mikrofonschleife laeuft ODER ein Messgeraet streamt.
        val mikrofonLaeuft = isRunning
        val messgeraetStreamt = connectionSupervisor.state.value == ConnectionState.STREAMING
        if (!mikrofonLaeuft && !messgeraetStreamt) return
        if (isRecordingActive.get()) return
        // Mindestens 1s Cooldown nach Beendigung der letzten WAV-Aufnahme
        if (System.currentTimeMillis() - letzteAufnahmeEndeTimestamp < 1000L) return

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
            letzterMeterFrame = meterFrame ?: letzterMeterFrame,
            mikrofonDb = mikrofonDb ?: letzterMikrofonDb,
            activeSchwelle = activeSchwelle,
            triggerQuelle = settingsManager.audioTriggerQuelle,
        )

        triggerWachhund.pegelGesehen(System.currentTimeMillis(), auswertung.ausgeloest)

        if (!auswertung.ausgeloest) return

        // Die WAV-Daten stammen ausschliesslich aus der Mikrofonschleife: writeChunk() wird nur
        // dort aufgerufen, und getPreRollData() liest den Rolling Buffer, der ebenfalls nur dort
        // gefuellt wird. Der PCE-323 liefert Pegelwerte ueber BLE, kein Audio. Ohne laufendes
        // Mikrofon eine WAV-Aufnahme zu starten wuerde eine Datei mit Header und leerem
        // Datenteil erzeugen - schlimmer als kein Beleg, weil sie wie einer aussieht.
        val wavMoeglich = settingsManager.recordWavAudio && mikrofonLaeuft

        if (wavMoeglich) {
            if (isRecordingActive.compareAndSet(false, true)) {
                serviceScope.launch(Dispatchers.IO) {
                    starteWavAufnahme(maxAmplitude, mikrofonDb ?: auswertung.pegel, auswertung, isQuiet)
                }
            }
            return
        }

        if (settingsManager.recordWavAudio && !mikrofonLaeuft) {
            meldeWavOhneMikrofon()
        }

        // Ein Ereignis mit Pegel, Zeitstempel und kalibriertem Wert - aber ohne Audio - ist
        // ungleich mehr wert als gar keins. Genau das fehlte in den 12 Stunden des Owners.
        if (isRecordingActive.compareAndSet(false, true)) {
            serviceScope.launch(Dispatchers.IO) {
                speicherePegelEreignisOhneAudio(auswertung, isQuiet, mikrofonDb)
            }
        }
    }

    /**
     * Weist einmal je Ueberwachungsperiode darauf hin, dass Ereignisse ohne Tonaufnahme
     * gespeichert werden. Die Kombination "Trigger = Messgeraet" + "WAV = an" +
     * "Mikrofon-Ueberwachung aus" ist technisch unmoeglich; bisher hat die App dazu geschwiegen.
     */
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

    /**
     * Der Wachhund gegen den stillen Ausfall (siehe [TriggerWachhund]).
     *
     * Die Meldung geht bewusst in die Notification und nicht nur ins Diagnose-Log: Der Owner hat
     * 12 Stunden lang nicht in den Diagnose-Screen geschaut, und dafuer gibt es keinen Grund.
     * Sie nennt ausserdem den vermuteten Grund, nicht nur den Zustand - "Trigger-Problem
     * erkannt" waere unbrauchbar.
     */
    private fun pruefeStillenAusfall() {
        val jetzt = System.currentTimeMillis()
        if (!triggerWachhund.stillerAusfall(jetzt)) return

        val minuten = triggerWachhund.dauerUeberSchwelleMs(jetzt) / 60_000L
        val grund = if (!isRunning && settingsManager.recordWavAudio) {
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
                "mikrofonLaeuft" to isRunning,
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
        // Peak-dB erfasst auch sehr kurze Geräuschimpulse (Knall, Hämmern, Rufen) sofort
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

    /** Erkennt, dass ueber der Schwelle gemessen wird, aber keine Ereignisse entstehen. */
    private val triggerWachhund = TriggerWachhund()

    /** Damit der Hinweis "Ereignisse ohne Ton" hoechstens einmal je Ueberwachungsperiode kommt. */
    @Volatile private var wavOhneMikrofonGemeldet = false

    /** Sichtbarer Text des Wachhunds, wird in die Notification uebernommen. */
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
        val sampleRate = settingsManager.audioSampleRate
        val durationMs = settingsManager.recordDurationSeconds * 1000L

        Log.d(
            "AudioRecordingService",
            "WAV-Aufnahme gestartet ($fileName): ${String.format(java.util.Locale.US, "%.1f", auswertung.pegel)} dB " +
                "(Quelle: ${if (auswertung.meterConnected) "Messgerät" else "Mikrofon"}, Ruhezeit=$isQuiet)",
        )

        var totalDataLen = 0L
        val fos = try {
            val stream = FileOutputStream(file)
            writeWavHeader(stream, channelConfig, sampleRate, audioFormat, 0)
            val preRoll = getPreRollData()
            stream.write(preRoll)
            totalDataLen = preRoll.size.toLong()
            stream
        } catch (e: Throwable) {
            Log.e("AudioRecordingService", "Fehler beim Anlegen der WAV-Datei", e)
            diagnosticsReporter.report(
                code = com.example.lrmprotokoll.diagnose.DiagnosticCode.AUDIO_FILE_WRITE_FAILED,
                component = "AudioRecordingService",
                operation = "starteWavAufnahme",
                severity = com.example.lrmprotokoll.diagnose.DiagnosticSeverity.ERROR,
                cause = e,
                details = mapOf("fileName" to fileName),
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

        val startWait = System.currentTimeMillis()
        while (System.currentTimeMillis() - startWait < durationMs && isRunning) {
            delay(50)
        }

        activeWavRecorder = null
        try {
            fos.close()
            updateWavHeader(file, recorder.totalDataLen)
        } catch (e: Throwable) {
            Log.e("AudioRecordingService", "Fehler beim Finalisieren der WAV-Datei", e)
        }

        val shouldClassifyOnline = settingsManager.aiMode == "ONLINE"
        // KI-Umbau Etappe 1.4: klassifiziereMitRohdaten() statt classifySafely()/classify() -
        // liefert zusaetzlich den RohdatenBauplan fuer die Persistenz, aus DERSELBEN Inferenz
        // (keine zweite Inferenz fuer die Rohdaten noetig). Wie classifySafely() jede Exception
        // abfangend: Klassifikation darf die Aufnahme nie gefaehrden.
        val ergebnis = if (shouldClassifyOnline) {
            try {
                classifier?.klassifiziereMitRohdaten(file)
            } catch (e: Throwable) {
                Log.w("AudioRecordingService", "Klassifikation fehlgeschlagen, Aufnahme laeuft trotzdem weiter", e)
                null
            }
        } else {
            null
        }
        val detected = ergebnis?.label

        try {
            val database = (application as LaermprotokollApp).container.database
            // Auch der WAV-Pfad meldet dem Wachhund ein Ereignis - sonst wuerde er anschlagen,
            // obwohl die Ausloesung einwandfrei funktioniert.
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
                    Log.e("AudioRecordingService", "Fehler beim Speichern der Klassifikations-Rohdaten", e)
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
            Log.i("AudioRecordingService", "NoiseRecord erfolgreich gespeichert: $fileName (Modus: ${settingsManager.aiMode}, KI: $detected)")
            // Nachtrag zu Etappe 1.1/1.3/1.4: fasst die fuer die Beweiskette relevanten Werte
            // dieser Aufnahme an EINER Stelle zusammen, damit der Owner sie ueber den
            // Support-Bundle-Export pruefen kann, ohne adb/Logcat zu benoetigen.
            diagnosticsReporter.breadcrumb(
                "AudioService",
                "NoiseRecord gespeichert (KI=$detected, Rohdaten=$rohdatenGespeichert)",
                data = mapOf(
                    "recordId" to neueId,
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
        }

        // WAV-Dateien verbleiben sicher auf dem Gerät und werden gebündelt alle 30 Minuten
        // über den periodischen DriveSyncWorker synchronisiert, um Drive-Rate-Limits zu vermeiden.

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
        _audioAufnahmeAktiv.value = false
        _currentMicDb.value = null
        _laeuft.value = false
        NoiseMonitoringWidgetProvider.updateAlleWidgets(applicationContext)
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
