@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

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
import com.example.lrmprotokoll.backup.SicherungManager
import com.example.lrmprotokoll.data.SettingsManager
import com.example.lrmprotokoll.data.erzeugeNtfyTopic
import com.example.lrmprotokoll.messreihe.RetentionVorschau
import com.example.lrmprotokoll.messreihe.SpeicherplatzUebersicht
import com.example.lrmprotokoll.messreihe.ermittleRetentionVorschau
import com.example.lrmprotokoll.messreihe.AufraeumErgebnis
import com.example.lrmprotokoll.messreihe.Speicherbelegung
import com.example.lrmprotokoll.messreihe.Speicheraufraeumer
import com.example.lrmprotokoll.messreihe.Speicherkategorie
import com.example.lrmprotokoll.messreihe.Speicherposten
import com.example.lrmprotokoll.messreihe.ermittleAufraeumVorschau
import com.example.lrmprotokoll.messreihe.ermittleSpeicherbelegung
import com.example.lrmprotokoll.messreihe.ermittleSpeicherplatz
import com.example.lrmprotokoll.messreihe.raeumeSpeicherAuf
import com.example.lrmprotokoll.messreihe.formatiereBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.lrmprotokoll.drive.DriveDatei
import com.example.lrmprotokoll.drive.DriveSyncCoordinator
import com.example.lrmprotokoll.drive.DriveSyncPlanung
import com.example.lrmprotokoll.drive.auth.AutorisierungBenoetigtException
import com.example.lrmprotokoll.ui.theme.TechBluePrimary
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenDrawer: (() -> Unit)? = null,
    /** Fuehrt zum In-App-Erklaerungsbildschirm der Laermerkennung. */
    onOpenKiErklaerung: (() -> Unit)? = null,
    onOpenDriveUploads: (() -> Unit)? = null,
    onShowSnackbar: ((String) -> Unit)? = null,
    /** Fuehrt zum Diagnose-Bildschirm. */
    onNavigateToDiagnose: (() -> Unit)? = null,
    onShowOnboarding: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as LaermprotokollApp).container }
    val settings = container.settingsManager
    val scope = rememberCoroutineScope()
    val verbindungszustand by container.connectionSupervisor.state.collectAsState()

    // Ansichtsmodus (Lite vs. Pro) & Presets-Dialog
    var isProMode by remember { mutableStateOf(settings.isProMode) }
    var expFoto by remember { mutableStateOf(false) }
    var expVideo by remember { mutableStateOf(false) }
    var expSpeicher by remember { mutableStateOf(false) }
    var belegung by remember { mutableStateOf<Speicherbelegung?>(null) }
    var gewaehlteKategorien by remember { mutableStateOf(setOf<Speicherkategorie>()) }
    var gewaehlterZeitraum by remember { mutableStateOf<Int?>(90) }
    var aufraeumVorschau by remember { mutableStateOf<Speicherposten?>(null) }
    var raeumtAuf by remember { mutableStateOf(false) }
    var videoMaxDauer by remember { mutableStateOf(settings.videoMaxDauerSekunden.toFloat()) }
    var videoAufloesung by remember { mutableStateOf(settings.videoAufloesung) }
    var videoDriveUpload by remember { mutableStateOf(settings.videoDriveUpload) }
    var fotoDokuAktiv by remember { mutableStateOf(settings.fotoDokuAktiv) }
    var fotoMessaufbau by remember { mutableStateOf(settings.fotoDokuMessaufbau) }
    var fotoKalibrierung by remember { mutableStateOf(settings.fotoDokuKalibrierung) }
    var fotoMax by remember { mutableFloatStateOf(settings.fotoDokuMaxProKategorie.toFloat()) }
    var fotoDriveUpload by remember { mutableStateOf(settings.fotoDokuDriveUpload) }
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
    var aiEinSchwelle by remember { mutableFloatStateOf(settings.aiEinSchwelle) }
    var aiAusSchwelle by remember { mutableFloatStateOf(settings.aiAusSchwelle) }
    var aiNormalisierung by remember { mutableStateOf(settings.aiNormalisierung) }

    // F8: Ruhezeiten
    var quietHoursEnabled by remember { mutableStateOf(settings.quietHoursEnabled) }
    var quietHoursStartHour by remember { mutableFloatStateOf(settings.quietHoursStartHour.toFloat()) }
    var quietHoursEndHour by remember { mutableFloatStateOf(settings.quietHoursEndHour.toFloat()) }
    var quietHoursThreshold by remember { mutableFloatStateOf(settings.quietHoursThreshold) }

    // F5: Auto-Bereinigung & Speicherplatz
    var autoRetentionEnabled by remember { mutableStateOf(settings.autoRetentionEnabled) }
    var autoRetentionDays by remember { mutableFloatStateOf(settings.autoRetentionDays.toFloat()) }
    var speicherplatz by remember { mutableStateOf<SpeicherplatzUebersicht?>(null) }
    var retentionVorschau by remember { mutableStateOf<RetentionVorschau?>(null) }

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
    var expSicherung by remember { mutableStateOf(false) }

    // F13 Sicherung und Wiederherstellung
    var sicherungLaeuft by remember { mutableStateOf(false) }
    var ausstehendeWiederherstellungUri by remember { mutableStateOf<Uri?>(null) }

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

    // F13 Sicherung: SAF-Dialog zum Wählen des Speicherorts, das eigentliche Schreiben passiert
    // in SicherungManager.erstelleSicherung.
    val sicherungErstellenLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                sicherungLaeuft = true
                val ergebnis = SicherungManager.erstelleSicherung(context, uri, settings)
                sicherungLaeuft = false
                onShowSnackbar?.invoke(ergebnis.nachricht)
            }
        }
    }

    // F13 Wiederherstellung: die eigentliche, destruktive Aktion (Datenbank überschreiben)
    // passiert erst nach Bestätigung im Warn-Dialog unten - hier nur die Datei auswählen.
    val sicherungEinspielenLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) ausstehendeWiederherstellungUri = uri
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
                        IconButton(onClick = onOpenDrawer, modifier = Modifier.size(48.dp).testTag("btn_navigation_drawer")) {
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
                        modifier = Modifier.testTag("btn_settings_mode_lite").weight(1f).height(40.dp),
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
                        modifier = Modifier.testTag("btn_settings_mode_pro").weight(1f).height(40.dp),
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
                    valueRange = 30f..100f,
                    modifier = Modifier.testTag("slider_db_threshold"),
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

                // Die Trigger-Quelle hing frueher in "if (recordWavAudio)" und war damit ohne
                // WAV-Aufnahme gar nicht einstellbar - obwohl auch ein reines Pegel-Ereignis
                // eine Quelle braucht und MeterTriggerSource weiterhin danach entscheidet.
                Spacer(modifier = Modifier.height(10.dp))
                Text(stringResource(R.string.settings_trigger_source_title), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(4.dp))
                // FlowRow statt Row: Ein Row bricht nicht um, er schneidet ab. "Automatisch
                // (Standard)" + "Nur PCE-323" + "Nur Mikrofon" passen zusammen nicht auf die
                // Breite eines ueblichen Telefons - der dritte Chip wurde rechts aus dem
                // sichtbaren Bereich geschoben und galt als "fehlt".
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
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
                                        ntfyTopic = erzeugeNtfyTopic()
                                        settings.ntfyTopic = ntfyTopic
                                    }
                                },
                                modifier = Modifier.testTag("switch_ntfy_enabled"),
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
                                modifier = Modifier.testTag("input_ntfy_server").fillMaxWidth(),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = ntfyTopic,
                                onValueChange = { ntfyTopic = it; settings.ntfyTopic = it },
                                label = { Text(stringResource(R.string.settings_alerting_ntfy_topic)) },
                                modifier = Modifier.testTag("input_ntfy_topic").fillMaxWidth(),
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

                Spacer(modifier = Modifier.height(6.dp))
                TextButton(onClick = { onOpenKiErklaerung?.invoke() }) {
                    Text("Wie die Lärmerkennung arbeitet – und wo ihre Grenzen liegen")
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

                // UX-Feedback: Einstellungen ist nur fuer die Default-Betriebsart zustaendig -
                // der eigentliche "jetzt klassifizieren"-Trigger lebt seitdem pro Aufnahme (Home,
                // ProtokollDetailScreen) bzw. pro Tag (Home-Tagesgruppen-Header), nicht mehr als
                // globaler Batch-Lauf hier.

                if (aiMode != "OFF" && isProMode) {
                    // KI-Umbau Etappe 2.6: die alte pauschale "KI-Vertrauensschwelle" (30% fuer
                    // jede Klasse) ist durch die Hysterese-Schwellung auf den Baulärm-
                    // Gruppen-Score ersetzt - Einstieg (aiEinSchwelle) und Ausstieg
                    // (aiAusSchwelle, niedriger als der Einstieg) statt einer Einheitsschwelle.
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(stringResource(R.string.settings_ai_ein_schwelle, (aiEinSchwelle * 100).toInt()))
                    Text(
                        stringResource(R.string.settings_ai_ein_schwelle_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = aiEinSchwelle,
                        onValueChange = { neuerWert ->
                            aiEinSchwelle = neuerWert
                            if (aiAusSchwelle >= neuerWert) aiAusSchwelle = (neuerWert - 0.05f).coerceAtLeast(0.05f)
                        },
                        onValueChangeFinished = {
                            settings.aiEinSchwelle = aiEinSchwelle
                            settings.aiAusSchwelle = aiAusSchwelle
                        },
                        valueRange = 0.10f..0.95f
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(stringResource(R.string.settings_ai_aus_schwelle, (aiAusSchwelle * 100).toInt()))
                    Text(
                        stringResource(R.string.settings_ai_aus_schwelle_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = aiAusSchwelle,
                        onValueChange = { aiAusSchwelle = it.coerceAtMost(aiEinSchwelle - 0.01f) },
                        onValueChangeFinished = { settings.aiAusSchwelle = aiAusSchwelle },
                        valueRange = 0.05f..0.90f
                    )

                    // KI-Umbau Etappe 1.6: Peak-Normalisierung vor der Inferenz - betrifft NUR
                    // den Inferenz-Puffer, nicht die gespeicherte WAV-Datei.
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_ai_normalisierung_title), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                stringResource(R.string.settings_ai_normalisierung_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = aiNormalisierung,
                            onCheckedChange = {
                                aiNormalisierung = it
                                settings.aiNormalisierung = it
                            }
                        )
                    }
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
                    // PROMPT_M10_FUNKTIONEN.md F5: erst beim Aufklappen ermitteln, nicht bei
                    // jedem Öffnen der Einstellungen - das Zählen der Audiodateien ist Datei-I/O.
                    LaunchedEffect(expRetention) {
                        if (expRetention) speicherplatz = ermittleSpeicherplatz(context)
                    }
                    speicherplatz?.let { belegung ->
                        Text(
                            text = stringResource(
                                R.string.settings_cleanup_storage_audio,
                                formatiereBytes(belegung.audioBytes)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = stringResource(
                                R.string.settings_cleanup_storage_database,
                                formatiereBytes(belegung.datenbankBytes)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_cleanup_title), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.settings_cleanup_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = autoRetentionEnabled,
                            onCheckedChange = { eingeschaltet ->
                                if (eingeschaltet) {
                                    // PROMPT_M10_FUNKTIONEN.md F5: "Vor der ersten Aktivierung
                                    // zeigen, wie viele Aufnahmen und wie viel Platz das jetzt
                                    // beträfe" - erst nach Bestätigung im Dialog unten wirklich
                                    // einschalten, nicht sofort hier.
                                    scope.launch {
                                        retentionVorschau = ermittleRetentionVorschau(
                                            container.database.noiseDao(),
                                            autoRetentionDays.toInt(),
                                        )
                                    }
                                } else {
                                    autoRetentionEnabled = false
                                    settings.autoRetentionEnabled = false
                                }
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

            retentionVorschau?.let { vorschau ->
                AlertDialog(
                    onDismissRequest = { retentionVorschau = null },
                    title = { Text(stringResource(R.string.settings_cleanup_preview_title)) },
                    text = {
                        Text(
                            stringResource(
                                R.string.settings_cleanup_preview_text,
                                vorschau.anzahlAufnahmen,
                                autoRetentionDays.toInt(),
                                formatiereBytes(vorschau.audioBytes),
                            )
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            autoRetentionEnabled = true
                            settings.autoRetentionEnabled = true
                            retentionVorschau = null
                        }) {
                            Text(stringResource(R.string.settings_cleanup_preview_confirm))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { retentionVorschau = null }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                )
            }

            // Sektion 6: Google Drive Synchronisation
            SettingsSectionCard(
                title = "Google Drive Synchronisation",
                summary = if (driveSyncAktiv && !googleAccountEmail.isNullOrBlank()) "Aktiv ($googleAccountEmail)" else if (!googleAccountEmail.isNullOrBlank()) "Verbunden (Pausiert)" else "Nicht verbunden",
                expanded = expDrive,
                onToggle = { expDrive = !expDrive }
            ) {
                // Owner-Wunsch: eine Seite, die zeigt, was hochgeladen wurde und was gerade
                // laeuft. Die Statuskarte darunter nennt nur den letzten Lauf als Ganzes.
                if (onOpenDriveUploads != null) {
                    TextButton(onClick = onOpenDriveUploads, modifier = Modifier.fillMaxWidth()) {
                        Text("Upload-Übersicht öffnen")
                    }
                }

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

            // Sektion 7: F13 Sicherung und Wiederherstellung
            SettingsSectionCard(
                title = stringResource(R.string.settings_backup_title),
                summary = stringResource(R.string.settings_backup_summary),
                expanded = expSicherung,
                onToggle = { expSicherung = !expSicherung }
            ) {
                Text(
                    text = stringResource(R.string.settings_backup_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = {
                        val dateiname = "laermprotokoll_sicherung_${System.currentTimeMillis()}.zip"
                        sicherungErstellenLauncher.launch(dateiname)
                    },
                    enabled = !sicherungLaeuft,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.settings_backup_create))
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { sicherungEinspielenLauncher.launch(arrayOf("application/zip")) },
                    enabled = !sicherungLaeuft,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.settings_backup_restore))
                }
                if (sicherungLaeuft) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            ausstehendeWiederherstellungUri?.let { uri ->
                AlertDialog(
                    onDismissRequest = { ausstehendeWiederherstellungUri = null },
                    title = { Text(stringResource(R.string.settings_backup_restore_warning_title)) },
                    text = { Text(stringResource(R.string.settings_backup_restore_warning_text)) },
                    confirmButton = {
                        TextButton(onClick = {
                            ausstehendeWiederherstellungUri = null
                            scope.launch {
                                sicherungLaeuft = true
                                val ergebnis = SicherungManager.spieleSicherungEin(context, uri, settings)
                                sicherungLaeuft = false
                                if (ergebnis.erfolg) {
                                    SicherungManager.starteNeustart(context)
                                } else {
                                    onShowSnackbar?.invoke(ergebnis.nachricht)
                                }
                            }
                        }) {
                            Text(
                                stringResource(R.string.settings_backup_restore_confirm),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { ausstehendeWiederherstellungUri = null }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                )
            }

            // Sektion 8: Diagnose & Systemgesundheit
            SettingsSectionCard(
                title = "Fotodokumentation",
                summary = if (fotoDokuAktiv) "Aktiv" else "Deaktiviert",
                expanded = expFoto,
                onToggle = { expFoto = !expFoto }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Beim Start eines Messvorgangs nach Fotos fragen")
                    Switch(checked = fotoDokuAktiv, onCheckedChange = {
                        fotoDokuAktiv = it
                        settings.fotoDokuAktiv = it
                    })
                }
                Text(
                    "Ein Foto vom Messaufbau belegt später, wie und wo gemessen wurde – die häufigste " +
                        "Entkräftung eines privaten Messprotokolls. Die Messung läuft dabei schon; " +
                        "sie wird nie durch die Fotoabfrage verzögert.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (fotoDokuAktiv) {
                    Spacer(modifier = Modifier.height(10.dp))
                    listOf(
                        Triple("Messaufbau", fotoMessaufbau) { wert: String ->
                            fotoMessaufbau = wert; settings.fotoDokuMessaufbau = wert
                        },
                        Triple("Kalibrierung", fotoKalibrierung) { wert: String ->
                            fotoKalibrierung = wert; settings.fotoDokuKalibrierung = wert
                        },
                    ).forEach { (bezeichnung, aktuell, setzen) ->
                        Text(bezeichnung, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            listOf("AUS" to "Aus", "OPTIONAL" to "Optional", "PFLICHT" to "Empfohlen").forEach { (wert, beschriftung) ->
                                FilterChip(
                                    selected = aktuell == wert,
                                    onClick = { setzen(wert) },
                                    label = { Text(beschriftung) },
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    Text(
                        "\"Empfohlen\" hebt die Kategorie hervor und hält eine Auslassung im Diagnoseprotokoll " +
                            "fest – die Messung wird nie blockiert.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (isProMode) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Höchstens ${fotoMax.toInt()} Fotos je Kategorie")
                        Slider(
                            value = fotoMax,
                            onValueChange = { fotoMax = it },
                            onValueChangeFinished = { settings.fotoDokuMaxProKategorie = fotoMax.toInt() },
                            valueRange = 1f..10f,
                            steps = 8,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Fotos nach Google Drive hochladen")
                            Switch(checked = fotoDriveUpload, onCheckedChange = {
                                fotoDriveUpload = it
                                settings.fotoDokuDriveUpload = it
                            })
                        }
                    }
                }
            }

            // Owner-Entscheidung E8: Anzeige des belegten Speichers und eine Aufraeumfunktion,
            // mit der sich Audioaufnahmen und/oder Videos fuer einen waehlbaren Zeitraum
            // freigeben lassen. Bewusst NICHT hinter dem Pro-Modus: Platz freizugeben ist
            // Grundbedarf, kein Komfortmerkmal - anders als die automatische Bereinigung, die
            // ungefragt loeschen wuerde und deshalb dort bleibt, wo sie war.
            SettingsSectionCard(
                title = "Speicherplatz",
                summary = belegung?.let { "Belegt: ${formatiereBytes(it.gesamtBytes)}" } ?: "Belegung anzeigen und freigeben",
                expanded = expSpeicher,
                onToggle = { expSpeicher = !expSpeicher }
            ) {
                // Erst beim Aufklappen ermitteln - das Zaehlen ist Datei-I/O.
                LaunchedEffect(expSpeicher) {
                    if (expSpeicher) belegung = ermittleSpeicherbelegung(context)
                }

                val aktuell = belegung
                if (aktuell == null) {
                    Text("Belegung wird ermittelt …", style = MaterialTheme.typography.bodySmall)
                } else {
                    Speicherkategorie.entries.forEach { kategorie ->
                        val posten = aktuell.posten(kategorie)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("${kategorie.anzeigename} (${posten.anzahl})", style = MaterialTheme.typography.bodyMedium)
                            Text(formatiereBytes(posten.bytes), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Berichte & Sonstiges (${aktuell.sonstigesAnzahl})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatiereBytes(aktuell.sonstigesBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Datenbank", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatiereBytes(aktuell.datenbankBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Auf dem Gerät noch frei", style = MaterialTheme.typography.bodyMedium)
                        Text(formatiereBytes(aktuell.freiBytes), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Text("Speicher freigeben", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Löscht nur die Dateien. Die Protokolleinträge bleiben erhalten – Zeitpunkt, " +
                            "Pegel und Klassifikation sind das eigentliche Protokoll, die Datei ist die Beilage.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Speicherkategorie.entries.forEach { kategorie ->
                            FilterChip(
                                selected = kategorie in gewaehlteKategorien,
                                onClick = {
                                    gewaehlteKategorien = if (kategorie in gewaehlteKategorien) {
                                        gewaehlteKategorien - kategorie
                                    } else {
                                        gewaehlteKategorien + kategorie
                                    }
                                },
                                label = { Text(kategorie.anzeigename) },
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Zeitraum", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf<Pair<Int?, String>>(
                            90 to "älter als 90 Tage",
                            30 to "älter als 30 Tage",
                            7 to "älter als 7 Tage",
                            null to "alles",
                        ).forEach { (tage, beschriftung) ->
                            FilterChip(
                                selected = gewaehlterZeitraum == tage,
                                onClick = { gewaehlterZeitraum = tage },
                                label = { Text(beschriftung) },
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                aufraeumVorschau = ermittleAufraeumVorschau(context, gewaehlteKategorien, gewaehlterZeitraum)
                            }
                        },
                        enabled = gewaehlteKategorien.isNotEmpty() && !raeumtAuf,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (raeumtAuf) "Wird freigegeben …" else "Freigeben …")
                    }
                    Text(
                        "Dateien der letzten Minuten bleiben immer erhalten – während einer " +
                            "laufenden Messung wird gerade an ihnen geschrieben.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            aufraeumVorschau?.let { vorschau ->
                AlertDialog(
                    onDismissRequest = { aufraeumVorschau = null },
                    title = { Text("Speicher freigeben?") },
                    text = {
                        Text(
                            if (vorschau.anzahl == 0) {
                                "Für diese Auswahl gibt es nichts freizugeben."
                            } else {
                                "${vorschau.anzahl} Dateien werden gelöscht und geben " +
                                    "${formatiereBytes(vorschau.bytes)} frei. Die zugehörigen " +
                                    "Protokolleinträge bleiben erhalten. Das lässt sich nicht rückgängig machen."
                            }
                        )
                    },
                    confirmButton = {
                        if (vorschau.anzahl > 0) {
                            TextButton(onClick = {
                                aufraeumVorschau = null
                                raeumtAuf = true
                                scope.launch {
                                    val ergebnis: AufraeumErgebnis =
                                        raeumeSpeicherAuf(context, gewaehlteKategorien, gewaehlterZeitraum)
                                    belegung = ermittleSpeicherbelegung(context)
                                    raeumtAuf = false
                                    onShowSnackbar?.invoke(
                                        if (ergebnis.fehlgeschlagen > 0) {
                                            "${ergebnis.geloescht} Dateien gelöscht (${formatiereBytes(ergebnis.bytes)}), " +
                                                "${ergebnis.fehlgeschlagen} konnten nicht gelöscht werden"
                                        } else {
                                            "${ergebnis.geloescht} Dateien gelöscht, ${formatiereBytes(ergebnis.bytes)} freigegeben"
                                        }
                                    )
                                }
                            }) {
                                Text("Endgültig löschen")
                            }
                        } else {
                            TextButton(onClick = { aufraeumVorschau = null }) { Text("OK") }
                        }
                    },
                    dismissButton = if (vorschau.anzahl > 0) {
                        { TextButton(onClick = { aufraeumVorschau = null }) { Text(stringResource(R.string.action_cancel)) } }
                    } else {
                        null
                    },
                )
            }

            SettingsSectionCard(
                title = "Videobeweis",
                summary = "Maximaldauer ${videoMaxDauer.toInt() / 60}:${String.format(java.util.Locale.GERMANY, "%02d", videoMaxDauer.toInt() % 60)} · " +
                    if (videoAufloesung == "FHD") "1080p" else "720p",
                expanded = expVideo,
                onToggle = { expVideo = !expVideo }
            ) {
                Text(
                    "Während einer laufenden Messung lässt sich im Cockpit ein Beweisvideo aufnehmen. " +
                        "Die Kamera nimmt dabei ohne Tonspur auf, damit die Pegelmessung ungestört " +
                        "weiterläuft – der Ton kommt aus der laufenden Messung und wird nach der " +
                        "Aufnahme in das Video eingefügt.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(10.dp))
                Text("Maximaldauer: ${videoMaxDauer.toInt() / 60} min ${videoMaxDauer.toInt() % 60} s")
                Slider(
                    value = videoMaxDauer,
                    onValueChange = { videoMaxDauer = it },
                    onValueChangeFinished = { settings.videoMaxDauerSekunden = videoMaxDauer.toInt() },
                    valueRange = 30f..900f,
                    steps = 28,
                )
                Text(
                    "Die Grenze schützt nicht nur den Speicher: Bild und Ton stammen aus zwei " +
                        "unabhängig getakteten Quellen, über sehr lange Aufnahmen können sie " +
                        "auseinanderlaufen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(10.dp))
                Text("Auflösung", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf("HD" to "720p (empfohlen)", "FHD" to "1080p").forEach { (wert, beschriftung) ->
                        FilterChip(
                            selected = videoAufloesung == wert,
                            onClick = { videoAufloesung = wert; settings.videoAufloesung = wert },
                            label = { Text(beschriftung) },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Videos nach Google Drive hochladen")
                    Switch(checked = videoDriveUpload, onCheckedChange = {
                        videoDriveUpload = it
                        settings.videoDriveUpload = it
                    })
                }
                Text(
                    "Standardmäßig aus – anders als bei Audio und Fotos. Ein Video kann Dritte, " +
                        "Kennzeichen und Wohnungsinneres zeigen und ist dabei um ein Vielfaches " +
                        "größer als alles andere, was die App speichert.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

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
                    // Der Knopf hat bisher ein Intent gebaut, es nie benutzt und nur den
                    // Bildschirm geschlossen - er tat also nicht, was auf ihm steht. Jetzt
                    // navigiert er wirklich zur Diagnose; ohne gesetzten Rueckruf bleibt das
                    // alte Verhalten als Rueckfall.
                    onClick = { onNavigateToDiagnose?.invoke() ?: onBack() },
                    modifier = Modifier.testTag("btn_open_diagnose").fillMaxWidth()
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Vollständige Diagnose aufrufen")
                }
            }

            // Sektion 9: Hilfe (PROMPT_M9_UX.md Aufgabe 8: Erststart-Onboarding muss aus den
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
