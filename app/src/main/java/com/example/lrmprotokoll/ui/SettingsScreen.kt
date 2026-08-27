package com.example.lrmprotokoll.ui

import android.app.AlarmManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.widget.Toast
import com.example.lrmprotokoll.AppContainer
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.alert.ChannelId
import com.example.lrmprotokoll.audio.AudioRecordingService
import com.example.lrmprotokoll.audio.NoiseClassifier
import com.example.lrmprotokoll.data.SettingsManager
import com.example.lrmprotokoll.data.erzeugeNtfyTopic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.lrmprotokoll.drive.DriveDatei
import com.example.lrmprotokoll.drive.DriveSyncCoordinator
import com.example.lrmprotokoll.drive.DriveSyncPlanung
import com.example.lrmprotokoll.drive.auth.AutorisierungBenoetigtException
import com.example.lrmprotokoll.ui.theme.TechBluePrimary
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenDrawer: (() -> Unit)? = null,
    onShowSnackbar: ((String) -> Unit)? = null,
    onShowOnboarding: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as LaermprotokollApp).container }
    val settings = container.settingsManager
    val scope = rememberCoroutineScope()
    val verbindungszustand by container.connectionSupervisor.state.collectAsState()

    // Ansichtsmodus (Lite vs. Pro) & Presets-Dialog
    var isProMode by remember { mutableStateOf(settings.isProMode) }
    var showWohnraumDialog by remember { mutableStateOf(false) }

    // F1 Schwellenwert-Assistent (PROMPT_M10_FUNKTIONEN.md): Live-Mikrofonpegel neben dem
    // Schwellen-Slider - null, solange die Überwachung nicht läuft.
    val audioAufnahmeAktiv by AudioRecordingService.audioAufnahmeAktiv.collectAsState()
    val currentMicDb by AudioRecordingService.currentMicDb.collectAsState()

    // Aufnahme-Parameter
    var dbThreshold by remember { mutableFloatStateOf(settings.dbThreshold) }
    var recordWavAudio by remember { mutableStateOf(settings.recordWavAudio) }
    var audioTriggerQuelle by remember { mutableStateOf(settings.audioTriggerQuelle) }
    var preRoll by remember { mutableFloatStateOf(settings.preRollSeconds.toFloat()) }
    var duration by remember { mutableFloatStateOf(settings.recordDurationSeconds.toFloat()) }
    var sampleRate by remember { mutableIntStateOf(settings.audioSampleRate) }

    // KI-Parameter
    var aiMode by remember { mutableStateOf(settings.aiMode) }
    var aiConfidence by remember { mutableFloatStateOf(settings.aiConfidenceThreshold) }
    var isBatchRunning by remember { mutableStateOf(false) }

    // F8: Ruhezeiten
    var quietHoursEnabled by remember { mutableStateOf(settings.quietHoursEnabled) }
    var quietHoursStartHour by remember { mutableFloatStateOf(settings.quietHoursStartHour.toFloat()) }
    var quietHoursEndHour by remember { mutableFloatStateOf(settings.quietHoursEndHour.toFloat()) }
    var quietHoursThreshold by remember { mutableFloatStateOf(settings.quietHoursThreshold) }

    // F5: Auto-Bereinigung & Speicherplatz
    var autoRetentionEnabled by remember { mutableStateOf(settings.autoRetentionEnabled) }
    var autoRetentionDays by remember { mutableFloatStateOf(settings.autoRetentionDays.toFloat()) }

    // Alarmierung
    var alarmierungAktiv by remember { mutableStateOf(settings.alarmierungAktiv) }
    var karenzzeit by remember { mutableFloatStateOf(settings.karenzzeitSekunden.toFloat()) }
    var ntfyAktiv by remember { mutableStateOf(settings.ntfyAktiv) }
    var ntfyServer by remember { mutableStateOf(settings.ntfyServer) }
    var ntfyTopic by remember { mutableStateOf(settings.ntfyTopic) }
    var heartbeatUrl by remember { mutableStateOf(settings.heartbeatUrl) }
    var entwarnungNtfy by remember { mutableStateOf(settings.entwarnungUeberNtfy) }
    var entwarnungMeldung by remember { mutableStateOf(settings.entwarnungUeberMeldung) }
    var alarmTonAktiv by remember { mutableStateOf(settings.alarmTonAktiv) }
    var testErgebnis by remember { mutableStateOf<String?>(null) }

    // Drive Sync
    var googleAccountEmail by remember { mutableStateOf(settings.googleAccountEmail) }
    var googleAccountName by remember { mutableStateOf(settings.googleAccountName) }
    var driveSyncAktiv by remember { mutableStateOf(settings.driveSyncEnabled) }
    var driveOrdnerName by remember { mutableStateOf(settings.driveFolderName) }
    var driveOrdnerId by remember { mutableStateOf(settings.driveFolderId) }
    var driveOrdnerBlockiert by remember { mutableStateOf(settings.driveOrdnerBlockiert) }
    var driveAggregationSekunden by remember { mutableFloatStateOf(settings.driveAggregationSekunden.toFloat()) }
    var driveWlanOnly by remember { mutableStateOf(settings.driveWlanOnly) }
    var driveUploadWav by remember { mutableStateOf(settings.driveUploadWav) }
    var driveEinrichtungsErgebnis by remember { mutableStateOf<String?>(settings.driveSyncLastMessage) }
    var ausstehendeZustimmung by remember { mutableStateOf<IntentSender?>(null) }
    var isSyncing by remember { mutableStateOf(false) }

    val syncHistorie by container.database.driveDailyFileDao().alle().collectAsState(initial = emptyList())
    val latestDailyFile = syncHistorie.firstOrNull()

    // Diagnose & Akku
    var diagnoseLoggingAktiv by remember { mutableStateOf(settings.diagnoseLoggingAktiv) }

    // Expandable Sektionszustände
    var expAufnahme by remember { mutableStateOf(false) }
    var expKi by remember { mutableStateOf(false) }
    var expRuhezeiten by remember { mutableStateOf(false) }
    var expRetention by remember { mutableStateOf(false) }
    var expAlarm by remember { mutableStateOf(false) }
    var expDrive by remember { mutableStateOf(false) }
    var expSystem by remember { mutableStateOf(false) }
    var expHilfe by remember { mutableStateOf(false) }

    suspend fun verarbeiteDriveEinrichtungsVersuch(ordner: String = driveOrdnerName) {
        when (val versuch = versucheDriveEinrichtung(container, settings, ordner, context)) {
            is DriveEinrichtungsVersuch.Erfolg -> {
                googleAccountEmail = settings.googleAccountEmail
                googleAccountName = settings.googleAccountName
                driveOrdnerId = versuch.folderId
                driveOrdnerBlockiert = false
                driveSyncAktiv = true
                driveEinrichtungsErgebnis = versuch.nachricht
                DriveSyncPlanung.plane(context)
            }
            is DriveEinrichtungsVersuch.Fehler -> driveEinrichtungsErgebnis = versuch.nachricht
            is DriveEinrichtungsVersuch.ZustimmungNoetig -> ausstehendeZustimmung = versuch.intentSender
        }
    }

    fun manuelleSynchronisation() {
        scope.launch {
            isSyncing = true
            try {
                val ergebnis = withContext(Dispatchers.IO) {
                    container.driveSyncCoordinator.syncEinenZyklus()
                }
                val msg = when (ergebnis) {
                    is DriveSyncCoordinator.SyncErgebnis.Erfolgreich -> "Synchronisation erfolgreich (${ergebnis.zeilen} Zeilen hochgeladen)"
                    is DriveSyncCoordinator.SyncErgebnis.KeineAenderung -> "Bereits aktuell (keine neuen Messwerte seit letztem Upload)"
                    is DriveSyncCoordinator.SyncErgebnis.Fehlgeschlagen -> "Fehlgeschlagen: ${ergebnis.grund}"
                    is DriveSyncCoordinator.SyncErgebnis.OrdnerNichtGefunden -> "Ordner nicht gefunden – bitte neu einrichten"
                    is DriveSyncCoordinator.SyncErgebnis.OrdnerBlockiert -> "Ordner blockiert"
                    is DriveSyncCoordinator.SyncErgebnis.KeinOrdnerEingerichtet -> "Kein Ordner eingerichtet"
                    is DriveSyncCoordinator.SyncErgebnis.SyncAusgeschaltet -> "Sync ist in den Einstellungen pausiert"
                }
                driveEinrichtungsErgebnis = msg
                onShowSnackbar?.invoke(msg) ?: Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            } finally {
                isSyncing = false
            }
        }
    }

    fun abmeldenDrive() {
        container.driveAccessTokenProvider.abmelden()
        googleAccountEmail = null
        googleAccountName = null
        driveOrdnerId = null
        driveSyncAktiv = false
        driveOrdnerBlockiert = false
        driveEinrichtungsErgebnis = "Google-Konto getrennt"
        DriveSyncPlanung.stoppe(context)
    }

    val driveZustimmungLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) {
        scope.launch { verarbeiteDriveEinrichtungsVersuch() }
    }

    LaunchedEffect(ausstehendeZustimmung) {
        ausstehendeZustimmung?.let { intentSender ->
            driveZustimmungLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            ausstehendeZustimmung = null
        }
    }

    val alarmManager = remember { context.getSystemService(AlarmManager::class.java) }
    fun kannExakteAlarme() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager?.canScheduleExactAlarms() == true
        } else true
    var exakteAlarmeErlaubt by remember { mutableStateOf(kannExakteAlarme()) }

    val powerManager = remember { context.getSystemService(PowerManager::class.java) }
    fun isIgnoringBatteryOptimizations() =
        powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    var batteryOptimizationIgnored by remember { mutableStateOf(isIgnoringBatteryOptimizations()) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryOptimizationIgnored = isIgnoringBatteryOptimizations()
                exakteAlarmeErlaubt = kannExakteAlarme()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (showWohnraumDialog) {
        RuhezeitPresetsDialog(
            aktuelleNachtSchwelle = quietHoursThreshold,
            onDismissRequest = { showWohnraumDialog = false },
            onPresetSelected = { nachtDb, tagDb ->
                quietHoursThreshold = nachtDb
                settings.quietHoursThreshold = nachtDb
                if (tagDb != null) {
                    dbThreshold = tagDb
                    settings.dbThreshold = tagDb
                }
                val msg = "Grenzwerte angepasst (Nacht: ${nachtDb.toInt()} dB${if (tagDb != null) ", Tag: ${tagDb.toInt()} dB" else ""})"
                onShowSnackbar?.invoke(msg) ?: Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_settings)) },
                navigationIcon = {
                    if (onOpenDrawer != null) {
                        IconButton(onClick = onOpenDrawer, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.action_menu))
                        }
                    } else {
                        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    }
                },
                actions = {
                    BluetoothStatusBadge(
                        state = verbindungszustand,
                        deviceName = settings.meterDeviceName,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Modus-Umschalter: Lite vs. Pro
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Button(
                        onClick = {
                            isProMode = false
                            settings.isProMode = false
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (!isProMode) TechBluePrimary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0f),
                            contentColor = if (!isProMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(40.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(AppIcons.Sparkle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.settings_mode_lite), fontWeight = if (!isProMode) FontWeight.Bold else FontWeight.Normal)
                    }

                    Button(
                        onClick = {
                            isProMode = true
                            settings.isProMode = true
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isProMode) TechBluePrimary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0f),
                            contentColor = if (isProMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(40.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.settings_mode_pro), fontWeight = if (isProMode) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }

            // Sektion 0: Sprache / Language
            var expSprache by remember { mutableStateOf(false) }
            var appLanguage by remember { mutableStateOf(settings.appLanguage) }
            SettingsSectionCard(
                title = stringResource(R.string.settings_language_title),
                summary = when (appLanguage) {
                    "de" -> stringResource(R.string.settings_language_de)
                    "en" -> stringResource(R.string.settings_language_en)
                    else -> stringResource(R.string.settings_language_system)
                },
                expanded = expSprache,
                onToggle = { expSprache = !expSprache }
            ) {
                Text(
                    text = stringResource(R.string.settings_language_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = appLanguage.isEmpty(),
                        onClick = {
                            appLanguage = ""
                            settings.appLanguage = ""
                            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                                androidx.core.os.LocaleListCompat.getEmptyLocaleList()
                            )
                        },
                        label = { Text(stringResource(R.string.settings_language_system)) }
                    )
                    FilterChip(
                        selected = appLanguage == "de",
                        onClick = {
                            appLanguage = "de"
                            settings.appLanguage = "de"
                            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                                androidx.core.os.LocaleListCompat.forLanguageTags("de")
                            )
                        },
                        label = { Text(stringResource(R.string.settings_language_de)) }
                    )
                    FilterChip(
                        selected = appLanguage == "en",
                        onClick = {
                            appLanguage = "en"
                            settings.appLanguage = "en"
                            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                                androidx.core.os.LocaleListCompat.forLanguageTags("en")
                            )
                        },
                        label = { Text(stringResource(R.string.settings_language_en)) }
                    )
                }
            }

            // Sektion 1: Aufnahme & Mikrofon
            SettingsSectionCard(
                title = stringResource(R.string.settings_section_thresholds),
                summary = "${String.format(Locale.getDefault(), "%.1f", dbThreshold)} dB Schwelle · ${if (recordWavAudio) "WAV-Audio aktiv" else "Reine Pegelmessung (Kein Audio)"}",
                expanded = expAufnahme,
                onToggle = { expAufnahme = !expAufnahme }
            ) {
                Text(stringResource(R.string.settings_threshold_day, dbThreshold), fontWeight = FontWeight.SemiBold)

                // F1 Schwellenwert-Assistent: aktueller Mikrofonpegel live neben dem Slider, als
                // Zahl und als Marker auf der Skala - ohne laufende Überwachung gibt es keinen
                // Live-Wert, dann bewusst nicht 0 dB anzeigen (PROMPT_M10_FUNKTIONEN.md Aufgabe 1).
                if (audioAufnahmeAktiv && currentMicDb != null) {
                    val aktuellerPegel = currentMicDb!!
                    Text(
                        text = stringResource(R.string.settings_threshold_current_level, aktuellerPegel),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(6.dp)) {
                        // Naeherung: der Slider hat intern einen Rand fuer den Thumb-Radius, den
                        // wir hier nicht kennen - reicht als grobe Orientierung auf der Skala,
                        // kein pixelgenauer Zeiger auf den Thumb.
                        val anteil = ((aktuellerPegel.toFloat() - 30f) / (100f - 30f)).coerceIn(0f, 1f)
                        Box(
                            modifier = Modifier
                                .offset(x = maxWidth * anteil)
                                .width(2.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.settings_threshold_no_live_level),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Slider(
                    value = dbThreshold,
                    onValueChange = { dbThreshold = it },
                    onValueChangeFinished = { settings.dbThreshold = dbThreshold },
                    valueRange = 30f..100f
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            currentMicDb?.let {
                                dbThreshold = schwellenvorschlagAufAktuellemPegel(it)
                                settings.dbThreshold = dbThreshold
                            }
                        },
                        enabled = audioAufnahmeAktiv && currentMicDb != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.settings_threshold_use_current))
                    }
                    OutlinedButton(
                        onClick = {
                            currentMicDb?.let {
                                dbThreshold = schwellenvorschlagMitSicherheitsabstand(it)
                                settings.dbThreshold = dbThreshold
                            }
                        },
                        enabled = audioAufnahmeAktiv && currentMicDb != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.settings_threshold_use_current_plus_5))
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = { showWohnraumDialog = true },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Home, contentDescription = null, modifier = Modifier.size(18.dp), tint = TechBluePrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_ta_laerm_presets), color = TechBluePrimary)
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Audioaufzeichnung an/aus (DSGVO / Datenschutz-Modus)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_record_wav_title), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.settings_record_wav_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = recordWavAudio,
                        onCheckedChange = {
                            recordWavAudio = it
                            settings.recordWavAudio = it
                        }
                    )
                }

                if (recordWavAudio) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(stringResource(R.string.settings_trigger_source_title), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = audioTriggerQuelle == "AUTO",
                            onClick = {
                                audioTriggerQuelle = "AUTO"
                                settings.audioTriggerQuelle = "AUTO"
                            },
                            label = { Text(stringResource(R.string.settings_trigger_source_auto)) }
                        )
                        FilterChip(
                            selected = audioTriggerQuelle == "PCE_323",
                            onClick = {
                                audioTriggerQuelle = "PCE_323"
                                settings.audioTriggerQuelle = "PCE_323"
                            },
                            label = { Text(stringResource(R.string.settings_trigger_source_meter)) }
                        )
                        FilterChip(
                            selected = audioTriggerQuelle == "MIKROFON",
                            onClick = {
                                audioTriggerQuelle = "MIKROFON"
                                settings.audioTriggerQuelle = "MIKROFON"
                            },
                            label = { Text(stringResource(R.string.settings_trigger_source_mic)) }
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_trigger_source_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (isProMode) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.settings_pre_roll, preRoll.toInt()))
                    Slider(
                        value = preRoll,
                        onValueChange = { preRoll = it },
                        onValueChangeFinished = { settings.preRollSeconds = preRoll.toInt() },
                        valueRange = 0f..5f,
                        steps = 4
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.settings_duration, duration.toInt()))
                    Slider(
                        value = duration,
                        onValueChange = { duration = it },
                        onValueChangeFinished = { settings.recordDurationSeconds = duration.toInt() },
                        valueRange = 1f..10f,
                        steps = 8
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.settings_sample_rate, sampleRate))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = sampleRate == 16000,
                            onClick = { sampleRate = 16000; settings.audioSampleRate = 16000 },
                            label = { Text(stringResource(R.string.settings_sample_rate_16k)) }
                        )
                        FilterChip(
                            selected = sampleRate == 44100,
                            onClick = { sampleRate = 44100; settings.audioSampleRate = 44100 },
                            label = { Text(stringResource(R.string.settings_sample_rate_44k)) }
                        )
                    }
                }
            }

            // Sektion 2: Alarmierung bei Verbindungsabbruch
            SettingsSectionCard(
                title = stringResource(R.string.settings_alerting_title),
                summary = if (alarmierungAktiv) "Aktiv${if (isProMode) " (Karenzzeit ${karenzzeit.toInt()}s)" else ""}" else "Deaktiviert",
                expanded = expAlarm,
                onToggle = { expAlarm = !expAlarm }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = alarmierungAktiv,
                        onCheckedChange = { alarmierungAktiv = it; settings.alarmierungAktiv = it },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_alerting_active))
                }

                if (alarmierungAktiv) {
                    if (isProMode) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(stringResource(R.string.settings_alerting_grace_period, karenzzeit.toInt()))
                        Slider(
                            value = karenzzeit,
                            onValueChange = { karenzzeit = it },
                            onValueChangeFinished = { settings.karenzzeitSekunden = karenzzeit.toInt() },
                            valueRange = 10f..900f,
                        )

                        if (!exakteAlarmeErlaubt) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Ohne Berechtigung für exakte Alarme kann die Karenzzeit verzögert ablaufen.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Button(onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                                }
                            }) {
                                Text("Exakte Alarme erlauben")
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Text(stringResource(R.string.settings_alerting_ntfy), style = MaterialTheme.typography.titleSmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = ntfyAktiv,
                                onCheckedChange = {
                                    ntfyAktiv = it
                                    settings.ntfyAktiv = it
                                    if (it && ntfyTopic.isBlank()) {
                                        settings.ntfyTopic = ntfyTopic
                                    }
                                },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.settings_alerting_ntfy))
                        }

                        if (ntfyAktiv) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = ntfyServer,
                                onValueChange = { ntfyServer = it; settings.ntfyServer = it },
                                label = { Text(stringResource(R.string.settings_alerting_ntfy_server)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = ntfyTopic,
                                onValueChange = { ntfyTopic = it; settings.ntfyTopic = it },
                                label = { Text(stringResource(R.string.settings_alerting_ntfy_topic)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Text(stringResource(R.string.settings_alerting_local), style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = alarmTonAktiv,
                                onCheckedChange = {
                                    alarmTonAktiv = it
                                    settings.alarmTonAktiv = it
                                },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.settings_alerting_sound))
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        OemDeviceHelperCard()
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    val alert = com.example.lrmprotokoll.alert.Alert(
                                        alertId = 0,
                                        kind = com.example.lrmprotokoll.alert.AlertKind.TEST,
                                        reason = com.example.lrmprotokoll.alert.AlertReason.DISCONNECTED,
                                        since = java.time.Instant.now(),
                                        message = "Test-Alarm: Verbindung zum Messgerät unterbrochen."
                                    )
                                    val res = com.example.lrmprotokoll.alert.local.LocalNotificationAlertChannel(context, settings).send(alert)
                                    testErgebnis = if (res.isSuccess) "Test-Alarm ausgelöst (Ton, Notification & Vibration)" else "Fehlgeschlagen: ${res.exceptionOrNull()?.message}"
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Test-Alarm")
                        }

                        OutlinedButton(
                            onClick = {
                                com.example.lrmprotokoll.alert.local.LocalNotificationAlertChannel.stoppeAlarmTon(context)
                                testErgebnis = "Alarmton gestoppt"
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Alarm stoppen")
                        }
                    }
                    testErgebnis?.let {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // Sektion 3: KI-Erkennung
            SettingsSectionCard(
                title = stringResource(R.string.settings_ai_title),
                summary = when (aiMode) {
                    "BATCH" -> "Im Batch (Standard / Empfohlen)"
                    "ONLINE" -> "Online / Live direkt"
                    else -> "Deaktiviert"
                },
                expanded = expKi,
                onToggle = { expKi = !expKi }
            ) {
                Text(
                    text = "Wähle, wann die KI-Geräuschklassifikation (YAMNet) ausgeführt werden soll:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = aiMode == "BATCH",
                        onClick = {
                            aiMode = "BATCH"
                            settings.aiMode = "BATCH"
                        },
                        label = { Text("Im Batch (Default)") },
                        modifier = Modifier.weight(1.1f)
                    )
                    FilterChip(
                        selected = aiMode == "ONLINE",
                        onClick = {
                            aiMode = "ONLINE"
                            settings.aiMode = "ONLINE"
                        },
                        label = { Text("Online / Live") },
                        modifier = Modifier.weight(1.0f)
                    )
                    FilterChip(
                        selected = aiMode == "OFF",
                        onClick = {
                            aiMode = "OFF"
                            settings.aiMode = "OFF"
                        },
                        label = { Text("Aus") },
                        modifier = Modifier.weight(0.7f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = when (aiMode) {
                        "BATCH" -> "Schont Akku und CPU während der kontinuierlichen Lärmmessung. Aufnahmen werden nach Abschluss der Messung oder im Hintergrund klassifiziert."
                        "ONLINE" -> "Klassifiziert jede Audioaufnahme sofort live im Moment der Schwellwertüberschreitung."
                        else -> "Keine automatische KI-Klassifikation von Geräuschen."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (aiMode != "OFF") {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                isBatchRunning = true
                                try {
                                    val count: Int = withContext(Dispatchers.IO) {
                                        val classifier = NoiseClassifier(context)
                                        classifier.classifyUnclassifiedBatch(container.database.noiseDao())
                                    }
                                    val msg = if (count > 0) "$count Aufnahme(n) erfolgreich nachträglich klassifiziert" else "Alle Aufnahmen sind bereits klassifiziert"
                                    onShowSnackbar?.invoke(msg) ?: Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                } finally {
                                    isBatchRunning = false
                                }
                            }
                        },
                        enabled = !isBatchRunning,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isBatchRunning) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Analysiere Aufnahmen...")
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Unklassifizierte Aufnahmen jetzt analysieren")
                        }
                    }
                }

                if (aiMode != "OFF" && isProMode) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(stringResource(R.string.settings_ai_confidence, (aiConfidence * 100).toInt()))
                    Slider(
                        value = aiConfidence,
                        onValueChange = { aiConfidence = it },
                        onValueChangeFinished = { settings.aiConfidenceThreshold = aiConfidence },
                        valueRange = 0.05f..0.95f
                    )
                }
            }

            // Sektion 4: F8 Ruhezeiten & Grenzwerte
            SettingsSectionCard(
                title = stringResource(R.string.settings_quiet_hours_title),
                summary = if (quietHoursEnabled) "Aktiv (${quietHoursStartHour.toInt()}:00 - ${quietHoursEndHour.toInt()}:00 Uhr · ${String.format(Locale.getDefault(), "%.1f", quietHoursThreshold)} dB)" else "Deaktiviert",
                expanded = expRuhezeiten,
                onToggle = { expRuhezeiten = !expRuhezeiten }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_quiet_hours_title), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.settings_quiet_hours_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = quietHoursEnabled,
                        onCheckedChange = {
                            quietHoursEnabled = it
                            settings.quietHoursEnabled = it
                        }
                    )
                }

                if (quietHoursEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.settings_quiet_hours_threshold, quietHoursThreshold))
                    Slider(
                        value = quietHoursThreshold,
                        onValueChange = { quietHoursThreshold = it },
                        onValueChangeFinished = { settings.quietHoursThreshold = quietHoursThreshold },
                        valueRange = 25f..80f
                    )

                    OutlinedButton(
                        onClick = { showWohnraumDialog = true },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Home, contentDescription = null, modifier = Modifier.size(18.dp), tint = TechBluePrimary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_ta_laerm_presets), color = TechBluePrimary)
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.settings_quiet_hours_start, quietHoursStartHour.toInt()))
                    Slider(
                        value = quietHoursStartHour,
                        onValueChange = { quietHoursStartHour = it },
                        onValueChangeFinished = { settings.quietHoursStartHour = quietHoursStartHour.toInt() },
                        valueRange = 0f..23f,
                        steps = 22
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.settings_quiet_hours_end, quietHoursEndHour.toInt()))
                    Slider(
                        value = quietHoursEndHour,
                        onValueChange = { quietHoursEndHour = it },
                        onValueChangeFinished = { settings.quietHoursEndHour = quietHoursEndHour.toInt() },
                        valueRange = 0f..23f,
                        steps = 22
                    )
                }
            }

            // Sektion 5: F5 Speicherplatz & Auto-Bereinigung (nur Pro-Modus)
            if (isProMode) {
                SettingsSectionCard(
                    title = stringResource(R.string.settings_cleanup_title),
                    summary = if (autoRetentionEnabled) "Auto-Bereinigung nach ${autoRetentionDays.toInt()} Tagen" else "Manuell",
                    expanded = expRetention,
                    onToggle = { expRetention = !expRetention }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_cleanup_title), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.settings_cleanup_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = autoRetentionEnabled,
                            onCheckedChange = {
                                autoRetentionEnabled = it
                                settings.autoRetentionEnabled = it
                            }
                        )
                    }

                    if (autoRetentionEnabled) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.settings_cleanup_days, autoRetentionDays.toInt()))
                        Slider(
                            value = autoRetentionDays,
                            onValueChange = { autoRetentionDays = it },
                            onValueChangeFinished = { settings.autoRetentionDays = autoRetentionDays.toInt() },
                            valueRange = 7f..180f
                        )
                    }
                }
            }

            // Sektion 6: Google Drive Synchronisation
            SettingsSectionCard(
                title = "Google Drive Synchronisation",
                summary = if (driveSyncAktiv && !googleAccountEmail.isNullOrBlank()) "Aktiv ($googleAccountEmail)" else if (!googleAccountEmail.isNullOrBlank()) "Verbunden (Pausiert)" else "Nicht verbunden",
                expanded = expDrive,
                onToggle = { expDrive = !expDrive }
            ) {
                DriveStatusCard(
                    googleAccountEmail = googleAccountEmail,
                    googleAccountName = googleAccountName,
                    syncEnabled = driveSyncAktiv,
                    folderName = driveOrdnerName,
                    folderId = driveOrdnerId,
                    isFolderBlocked = driveOrdnerBlockiert,
                    consecutiveFailures = settings.driveSyncFehlschlaegeInFolge,
                    lastSuccessAt = settings.driveSyncLastSuccessAt,
                    lastMessage = driveEinrichtungsErgebnis,
                    latestDailyFile = latestDailyFile,
                    isSyncing = isSyncing,
                    onToggleSync = {
                        driveSyncAktiv = it
                        settings.driveSyncEnabled = it
                        if (it) DriveSyncPlanung.plane(context) else DriveSyncPlanung.stoppe(context)
                    },
                    onSyncNow = { manuelleSynchronisation() },
                    onConnectGoogle = { scope.launch { verarbeiteDriveEinrichtungsVersuch() } },
                    onDisconnectGoogle = { abmeldenDrive() },
                    onUpdateFolderName = { newFolder ->
                        driveOrdnerName = newFolder
                        settings.driveFolderName = newFolder
                        scope.launch { verarbeiteDriveEinrichtungsVersuch(newFolder) }
                    },
                    onLoadFolders = {
                        withContext(Dispatchers.IO) {
                            container.driveEinrichtung.ladeVerfuegbareOrdner()
                        }
                    },
                    onSelectFolder = { selectedFolder ->
                        driveOrdnerName = selectedFolder.name
                        driveOrdnerId = selectedFolder.id
                        scope.launch {
                            container.driveEinrichtung.waehleBestehendenOrdner(selectedFolder)
                            driveSyncAktiv = true
                            DriveSyncPlanung.plane(context)
                        }
                    },
                    onCreateFolder = { newName ->
                        withContext(Dispatchers.IO) {
                            container.driveEinrichtung.erstelleNeuenOrdner(newName).also { res ->
                                res.getOrNull()?.let { f ->
                                    driveOrdnerName = f.name
                                    driveOrdnerId = f.id
                                    driveSyncAktiv = true
                                    DriveSyncPlanung.plane(context)
                                }
                            }
                        }
                    },
                    onRenameFolder = { fId, newName ->
                        withContext(Dispatchers.IO) {
                            container.driveEinrichtung.benenneOrdnerUm(fId, newName).also { res ->
                                if (res.isSuccess && driveOrdnerId == fId) {
                                    driveOrdnerName = newName
                                }
                            }
                        }
                    }
                )

                if (driveSyncAktiv || !googleAccountEmail.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Upload-Optionen", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = driveUploadWav,
                            onCheckedChange = { driveUploadWav = it; settings.driveUploadWav = it },
                        )
                        Text("Audioaufnahmen (WAV) hochladen")
                    }

                    if (isProMode) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = driveWlanOnly,
                                onCheckedChange = {
                                    driveWlanOnly = it
                                    settings.driveWlanOnly = it
                                    DriveSyncPlanung.plane(context)
                                },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Nur über WLAN synchronisieren")
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Aggregationsfenster: ${driveAggregationSekunden.toInt()} s",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Zusammenfassungs-Intervall für Pegelmesswerte in der Google Drive CSV-Tabelle (z. B. 10 s oder 60 s Mittelwert).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = driveAggregationSekunden,
                            onValueChange = {
                                driveAggregationSekunden = it
                                settings.driveAggregationSekunden = it.toInt()
                            },
                            valueRange = 1f..60f,
                            steps = 58,
                        )
                    }
                }
            }

            // Sektion 7: Diagnose & Systemgesundheit
            SettingsSectionCard(
                title = stringResource(R.string.settings_section_diagnostics),
                summary = "Systemstatus, Sensoren, Berechtigungen & Ereignis-Log",
                expanded = expSystem,
                onToggle = { expSystem = !expSystem }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = diagnoseLoggingAktiv,
                        onCheckedChange = {
                            diagnoseLoggingAktiv = it
                            settings.diagnoseLoggingAktiv = it
                            if (it) {
                                com.example.lrmprotokoll.diagnose.DiagnosticLogCleanupPlanung.plane(context)
                            } else {
                                com.example.lrmprotokoll.diagnose.DiagnosticLogCleanupPlanung.stoppe(context)
                            }
                        },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Diagnose-Log aktiv")
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text("Akku-Optimierung", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (batteryOptimizationIgnored) "Diese App ist von der Akku-Optimierung ausgenommen. Die Hintergrund-Überwachung läuft zuverlässig."
                    else "Eingeschränkt. Bitte Ausnahme in Systemeinstellungen aktivieren.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        val intent = Intent(context, MainActivity::class.java).apply {
                            // Über Drawer/Navigation erreichbar
                        }
                        onBack()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Vollständige Diagnose aufrufen")
                }
            }

            // Sektion 8: Hilfe (PROMPT_M9_UX.md Aufgabe 8: Erststart-Onboarding muss aus den
            // Einstellungen jederzeit wieder aufrufbar sein, nicht nur einmalig beim ersten Start)
            if (onShowOnboarding != null) {
                SettingsSectionCard(
                    title = stringResource(R.string.settings_help_title),
                    summary = stringResource(R.string.settings_help_summary),
                    expanded = expHilfe,
                    onToggle = { expHilfe = !expHilfe }
                ) {
                    OutlinedButton(
                        onClick = onShowOnboarding,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.settings_help_show_onboarding))
                    }
                }
            }

            Spacer(modifier = Modifier.fillMaxWidth().height(8.dp).testTag(BILDSCHIRM_ENDE_TAG))
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    com.example.lrmprotokoll.ui.components.NoiseCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() }
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    content()
                }
            }
        }
    }
}

const val BILDSCHIRM_ENDE_TAG = "settings_bildschirm_ende"

internal fun formatiereDriveFehler(fehler: Throwable): String {
    val ursache = fehler.cause?.message
    return if (ursache != null) {
        "Verbindung fehlgeschlagen: ${fehler.message} ($ursache)"
    } else {
        "Verbindung fehlgeschlagen: ${fehler.message}"
    }
}

internal sealed interface DriveEinrichtungsVersuch {
    data class Erfolg(val nachricht: String, val folderId: String?) : DriveEinrichtungsVersuch
    data class Fehler(val nachricht: String) : DriveEinrichtungsVersuch
    data class ZustimmungNoetig(val intentSender: IntentSender) : DriveEinrichtungsVersuch
}

internal suspend fun versucheDriveEinrichtung(
    container: AppContainer,
    settings: SettingsManager,
    ordnerName: String,
    context: Context? = null,
): DriveEinrichtungsVersuch {
    val name = ordnerName.ifBlank { "Lärmprotokoll" }
    return container.driveEinrichtung.richteEin(name, context).fold(
        onSuccess = {
            DriveEinrichtungsVersuch.Erfolg(
                nachricht = "Verbunden. Ordner \"$name\" wurde angelegt.",
                folderId = settings.driveFolderId,
            )
        },
        onFailure = { fehler ->
            val zustimmung = findeAutorisierungBenoetigt(fehler)
            if (zustimmung != null) {
                DriveEinrichtungsVersuch.ZustimmungNoetig(zustimmung.intentSender)
            } else {
                DriveEinrichtungsVersuch.Fehler(formatiereDriveFehler(fehler))
            }
        },
    )
}

internal tailrec fun findeAutorisierungBenoetigt(fehler: Throwable?): AutorisierungBenoetigtException? =
    when (fehler) {
        null -> null
        is AutorisierungBenoetigtException -> fehler
        else -> findeAutorisierungBenoetigt(fehler.cause)
    }
