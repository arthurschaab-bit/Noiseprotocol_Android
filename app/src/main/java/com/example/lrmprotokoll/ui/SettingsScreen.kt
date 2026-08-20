package com.example.lrmprotokoll.ui

import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.app.AlarmManager
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import com.example.lrmprotokoll.AppContainer
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.alert.ChannelId
import com.example.lrmprotokoll.data.SettingsManager
import com.example.lrmprotokoll.data.erzeugeNtfyTopic
import com.example.lrmprotokoll.drive.DriveSyncPlanung
import com.example.lrmprotokoll.drive.auth.AutorisierungBenoetigtException
import kotlinx.coroutines.launch
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

    val container = remember { (context.applicationContext as LaermprotokollApp).container }
    val scope = rememberCoroutineScope()
    var alarmierungAktiv by remember { mutableStateOf(settings.alarmierungAktiv) }
    var karenzzeit by remember { mutableFloatStateOf(settings.karenzzeitSekunden.toFloat()) }
    var ntfyAktiv by remember { mutableStateOf(settings.ntfyAktiv) }
    var ntfyServer by remember { mutableStateOf(settings.ntfyServer) }
    var ntfyTopic by remember { mutableStateOf(settings.ntfyTopic) }
    var heartbeatUrl by remember { mutableStateOf(settings.heartbeatUrl) }
    var entwarnungNtfy by remember { mutableStateOf(settings.entwarnungUeberNtfy) }
    var entwarnungMeldung by remember { mutableStateOf(settings.entwarnungUeberMeldung) }
    var testErgebnis by remember { mutableStateOf<String?>(null) }

    var driveSyncAktiv by remember { mutableStateOf(settings.driveSyncEnabled) }
    var driveOrdnerName by remember { mutableStateOf(settings.driveFolderName) }
    var driveOrdnerId by remember { mutableStateOf(settings.driveFolderId) }
    var driveOrdnerBlockiert by remember { mutableStateOf(settings.driveOrdnerBlockiert) }
    var driveAggregationSekunden by remember { mutableFloatStateOf(settings.driveAggregationSekunden.toFloat()) }
    var driveWlanOnly by remember { mutableStateOf(settings.driveWlanOnly) }
    var driveUploadWav by remember { mutableStateOf(settings.driveUploadWav) }
    var driveEinrichtungsErgebnis by remember { mutableStateOf<String?>(null) }

    // Google verlangt beim allerersten Autorisieren des drive.file-Scopes (oder nach einem
    // Widerruf) einen expliziten Zustimmungsbildschirm (siehe AutorisierungBenoetigtException-
    // KDoc). ausstehendeZustimmung + der LaunchedEffect darunter starten ihn, sobald er gesetzt
    // wird - statt den Launcher direkt aus seinem eigenen Ergebnis-Callback heraus erneut
    // aufzurufen (das waere ein unaufloesbarer Selbstbezug auf eine lokale val, kein gueltiger
    // Kotlin-Code).
    var ausstehendeZustimmung by remember { mutableStateOf<IntentSender?>(null) }

    suspend fun verarbeiteDriveEinrichtungsVersuch() {
        when (val versuch = versucheDriveEinrichtung(container, settings, driveOrdnerName)) {
            is DriveEinrichtungsVersuch.Erfolg -> {
                driveOrdnerId = versuch.folderId
                driveOrdnerBlockiert = false
                driveEinrichtungsErgebnis = versuch.nachricht
            }
            is DriveEinrichtungsVersuch.Fehler -> driveEinrichtungsErgebnis = versuch.nachricht
            is DriveEinrichtungsVersuch.ZustimmungNoetig -> ausstehendeZustimmung = versuch.intentSender
        }
    }

    val driveZustimmungLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) {
        // Ergebnis bewusst nicht ausgewertet: Google verlangt fuer die Authorization API, nach
        // Abschluss des Zustimmungsbildschirms - egal ob erteilt oder abgebrochen - erneut
        // authorize() aufzurufen; bei Erteilung liefert das dann direkt ein Token, sonst wieder
        // denselben (oder gar keinen) Zustimmungsbedarf.
        scope.launch { verarbeiteDriveEinrichtungsVersuch() }
    }

    LaunchedEffect(ausstehendeZustimmung) {
        ausstehendeZustimmung?.let { intentSender ->
            driveZustimmungLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            ausstehendeZustimmung = null
        }
    }

    var diagnoseLoggingAktiv by remember { mutableStateOf(settings.diagnoseLoggingAktiv) }

    val alarmManager = remember { context.getSystemService(AlarmManager::class.java) }
    fun kannExakteAlarme() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager?.canScheduleExactAlarms() == true
        } else {
            true
        }
    var exakteAlarmeErlaubt by remember { mutableStateOf(kannExakteAlarme()) }

    val powerManager = remember { context.getSystemService(PowerManager::class.java) }
    fun isIgnoringBatteryOptimizations() =
        powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    var batteryOptimizationIgnored by remember { mutableStateOf(isIgnoringBatteryOptimizations()) }

    // Der Systemdialog laeuft in einer eigenen Activity - beim Zurueckkehren (ON_RESUME) den
    // tatsaechlichen Status neu lesen, statt ihn optimistisch anzunehmen.
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
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("Aufnahme-Schwellenwert (Mikrofon): ${String.format(Locale.getDefault(), "%.1f", dbThreshold)} dB")
            Text(
                "Gilt nur, solange kein Messgerät verbunden ist. Bei bestehender Messgerät-" +
                    "Verbindung wird durchgehend aufgezeichnet, unabhängig vom Pegel.",
                style = MaterialTheme.typography.bodySmall,
            )
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

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text("Alarmierung bei Verbindungsabbruch", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Reißt die Verbindung zum Messgerät ab und kommt sie nicht innerhalb der " +
                    "Karenzzeit zurück, wird alarmiert.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = alarmierungAktiv,
                    onCheckedChange = { alarmierungAktiv = it; settings.alarmierungAktiv = it },
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Alarmierung aktiv")
            }

            if (alarmierungAktiv) {
                Spacer(modifier = Modifier.height(12.dp))
                Text("Karenzzeit: ${karenzzeit.toInt()} s")
                Text(
                    "Kurze Aussetzer sind im Betrieb normal. Ohne Karenzzeit meldet die App " +
                        "jeden davon - und wird nach einer Woche stummgeschaltet.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Slider(
                    value = karenzzeit,
                    onValueChange = { karenzzeit = it },
                    onValueChangeFinished = { settings.karenzzeitSekunden = karenzzeit.toInt() },
                    valueRange = 10f..900f,
                )

                if (!exakteAlarmeErlaubt) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Ohne die Berechtigung für exakte Alarme kann die Karenzzeit im " +
                            "Energiesparmodus deutlich verspätet ablaufen. Der Alarm geht dann " +
                            "immer noch raus, aber unter Umständen erst viel später.",
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
                Text("Push auf ein zweites Gerät (ntfy)", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = ntfyAktiv,
                        onCheckedChange = {
                            ntfyAktiv = it
                            settings.ntfyAktiv = it
                            // Beim ersten Einschalten sofort ein Topic erzeugen - sonst waere der
                            // Kanal scheinbar an, aber ohne Ziel.
                            if (it && ntfyTopic.isBlank()) {
                                ntfyTopic = erzeugeNtfyTopic()
                                settings.ntfyTopic = ntfyTopic
                            }
                        },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Push aktiv")
                }

                if (ntfyAktiv) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = ntfyServer,
                        onValueChange = { ntfyServer = it },
                        label = { Text("ntfy-Server") },
                        supportingText = { Text("Standard: https://ntfy.sh. Eigene Instanz möglich.") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = { settings.ntfyServer = ntfyServer; ntfyServer = settings.ntfyServer }) {
                        Text("Server übernehmen")
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Topic", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Auf dem öffentlichen Server ist dieser Name das einzige Geheimnis: Wer " +
                            "ihn kennt, liest alle Alarme mit und kann selbst welche senden. " +
                            "Niemandem zeigen, den Sie nicht alarmieren wollen.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(ntfyTopic.ifBlank { "– noch keines erzeugt –" })
                    Row {
                        TextButton(onClick = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard?.setPrimaryClip(ClipData.newPlainText("ntfy-Topic", ntfyTopic))
                        }) { Text("Kopieren") }
                        TextButton(onClick = {
                            ntfyTopic = erzeugeNtfyTopic()
                            settings.ntfyTopic = ntfyTopic
                        }) { Text("Neu erzeugen") }
                    }
                    Text(
                        "Auf dem zweiten Gerät die ntfy-App installieren und genau dieses Topic " +
                            "abonnieren.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text("Entwarnung senden", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = entwarnungNtfy,
                        onCheckedChange = { entwarnungNtfy = it; settings.entwarnungUeberNtfy = it },
                    )
                    Text("per Push")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = entwarnungMeldung,
                        onCheckedChange = { entwarnungMeldung = it; settings.entwarnungUeberMeldung = it },
                    )
                    Text("als Meldung auf diesem Gerät")
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text("Totmannschaltung", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Jeder Alarm ist eine Nachricht dieses Geräts. Stirbt es selbst - Akku leer, " +
                        "App abgeschossen, kein Internet - kommt gar nichts mehr, und der " +
                        "Ausfall bleibt unbemerkt. Dagegen hilft nur die umgekehrte Richtung: " +
                        "Dieses Gerät meldet sich alle 15 Minuten bei einem Dienst; bleibt die " +
                        "Meldung aus, alarmiert der Dienst Sie.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = heartbeatUrl,
                    onValueChange = { heartbeatUrl = it },
                    label = { Text("Ping-URL (z. B. healthchecks.io)") },
                    supportingText = { Text("Leer lassen schaltet die Totmannschaltung ab.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { settings.heartbeatUrl = heartbeatUrl; heartbeatUrl = settings.heartbeatUrl }) {
                    Text("Ping-URL übernehmen")
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text("Probealarm", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Ein Alarmkanal, der erst im Ernstfall zum ersten Mal benutzt wird, ist kein " +
                        "Alarmkanal, sondern eine Hoffnung. Jetzt testen.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row {
                    Button(onClick = {
                        scope.launch {
                            testErgebnis = container.alarmCoordinator.sendeTest(ChannelId.NTFY)
                                .fold(
                                    onSuccess = { "Push wurde angenommen. Kam er auf dem zweiten Gerät an?" },
                                    onFailure = { "Push fehlgeschlagen: ${it.message}" },
                                )
                        }
                    }) { Text("Test-Push") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = {
                        scope.launch {
                            testErgebnis = container.alarmCoordinator
                                .sendeTest(ChannelId.LOCAL_NOTIFICATION)
                                .fold(
                                    onSuccess = { "Meldung auf diesem Gerät ausgelöst." },
                                    onFailure = { "Meldung fehlgeschlagen: ${it.message}" },
                                )
                        }
                    }) { Text("Test-Meldung") }
                }
                testErgebnis?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(it)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Ein angenommener Push heißt nur, dass der Server ihn entgegengenommen hat - " +
                        "nicht, dass er angekommen ist. Deshalb bitte am zweiten Gerät nachsehen.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }


            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text("Google-Drive-Synchronisation", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Lädt die Pegelwerte alle 30 Minuten in einen selbst gewählten Drive-Ordner hoch " +
                    "- eine Datei pro Tag, die aktualisiert statt dupliziert wird.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = driveSyncAktiv,
                    onCheckedChange = {
                        driveSyncAktiv = it
                        settings.driveSyncEnabled = it
                        if (it) {
                            DriveSyncPlanung.plane(context)
                        } else {
                            DriveSyncPlanung.stoppe(context)
                        }
                    },
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Synchronisation aktiv")
            }

            if (driveSyncAktiv) {
                Spacer(modifier = Modifier.height(12.dp))

                if (driveOrdnerBlockiert) {
                    Text(
                        "Der eingerichtete Ordner wurde nicht gefunden - bitte neu verbinden.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                if (driveOrdnerId == null) {
                    Text(
                        "Noch nicht mit Google verbunden.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text("Ordner: $driveOrdnerName")
                }
                Spacer(modifier = Modifier.height(4.dp))
                Button(onClick = {
                    scope.launch { verarbeiteDriveEinrichtungsVersuch() }
                }) {
                    Text(if (driveOrdnerId == null) "Mit Google verbinden" else "Ordner neu einrichten")
                }
                driveEinrichtungsErgebnis?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text("Aufzeichnungsgenauigkeit: ${driveAggregationSekunden.toInt()} s", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Je feiner, desto mehr Uploadvolumen. Die Rohwerte bleiben davon unberührt " +
                        "immer vollständig auf dem Gerät erhalten - das betrifft nur die Drive-Datei.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Slider(
                    value = driveAggregationSekunden,
                    onValueChange = { driveAggregationSekunden = it },
                    onValueChangeFinished = {
                        settings.driveAggregationSekunden = driveAggregationSekunden.toInt()
                    },
                    valueRange = 1f..60f,
                )

                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = driveWlanOnly,
                        onCheckedChange = {
                            driveWlanOnly = it
                            settings.driveWlanOnly = it
                            if (driveSyncAktiv) DriveSyncPlanung.plane(context) // Constraint neu setzen
                        },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Nur über WLAN synchronisieren")
                }
                Text(
                    "Bei feiner Aufzeichnungsgenauigkeit kann täglich ein zweistelliger " +
                        "MB-Betrag zusammenkommen. Empfohlen, solange kein unbegrenzter Datentarif besteht.",
                    style = MaterialTheme.typography.bodySmall,
                )

                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = driveUploadWav,
                        onCheckedChange = { driveUploadWav = it; settings.driveUploadWav = it },
                    )
                    Text("Audioaufnahmen (WAV) ebenfalls hochladen")
                }
                Text(
                    "WAV-Dateien können Sprache Dritter enthalten und sind eine andere " +
                        "Datenschutzkategorie als reine Pegelwerte - standardmäßig an, bei Bedarf hier abschaltbar.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text("Diagnose-Log", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Zeichnet technische Ereignisse rund um die Bluetooth-Verbindung im App-internen " +
                    "Speicher auf, zur Fehlersuche bei Verbindungsproblemen: erfolgreiche und " +
                    "gescheiterte Verbindungsversuche, Reconnect-Versuche mit Backoff-Wartezeit, " +
                    "Datenstillstand, hohe Fehlerrate, auffällige Framekadenz, " +
                    "Bluetooth-Adapter aus/an sowie endgültige Fehlschläge und Wiederherstellungen. " +
                    "Einträge werden nach 7 Tagen automatisch gelöscht. Standardmäßig aus.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
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

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text("Akku-Optimierung", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            if (batteryOptimizationIgnored) {
                Text(
                    "Diese App ist von der Akku-Optimierung ausgenommen. Die Messgeräte-" +
                        "Überwachung im Hintergrund läuft damit zuverlässig weiter."
                )
            } else {
                Text(
                    "Ohne Ausnahme von der Akku-Optimierung beendet das Betriebssystem die " +
                        "Bluetooth-Verbindung zum Messgerät im Hintergrund oft vorzeitig, " +
                        "besonders bei Herstellern wie Xiaomi, Huawei oder Samsung. Mit der " +
                        "Ausnahme bleibt die Verbindung auch bei ausgeschaltetem Bildschirm " +
                        "bestehen. Das kann den Akkuverbrauch leicht erhöhen."
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = {
                    val intent = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}")
                    )
                    context.startActivity(intent)
                }) {
                    Text("Akku-Optimierung deaktivieren")
                }
            }

            // Fuer den Compose-Regressionstest (M7c Aufgabe 4): unbedingt am Bildschirmende
            // vorhanden, unabhaengig vom batteryOptimizationIgnored-Zweig oben. fillMaxWidth()
            // noetig, sonst hat der Spacer Breite 0 und assertIsDisplayed() haelt ihn faelschlich
            // fuer nicht sichtbar.
            Spacer(modifier = Modifier.fillMaxWidth().height(8.dp).testTag(BILDSCHIRM_ENDE_TAG))
        }
    }
}

const val BILDSCHIRM_ENDE_TAG = "settings_bildschirm_ende"

/**
 * Owner-Rückmeldung: "beim Google Drive Synchronisation 'Mit Google verbinden' kommt nur
 * 'Verbindung fehlgeschlagen: Kein Zugriffstoken verfügbar'." Diese Meldung stammt aus
 * [com.example.lrmprotokoll.drive.GoogleDriveApiClient]s `mitToken()`, das JEDEN Fehler beim
 * Tokenabruf pauschal in "Kein Zugriffstoken verfügbar" verpackt (mit der eigentlichen Ursache
 * nur als `cause`, siehe dortiger KDoc) - z. B. verschluckt das die viel konkretere Meldung aus
 * [com.example.lrmprotokoll.drive.auth.GoogleSignInAccessTokenProvider] ("Keine echte
 * OAuth-Client-ID eingerichtet - siehe GoogleClientConfig"), solange keine echte
 * [com.example.lrmprotokoll.drive.auth.GoogleClientConfig.SERVER_CLIENT_ID] eingerichtet ist.
 * Bislang zeigte die UI nur `fehler.message` - die tatsächliche Ursache blieb dem Nutzer
 * verborgen. Diese Funktion haengt die `cause`-Meldung an, falls vorhanden, statt sie zu
 * verwerfen.
 */
internal fun formatiereDriveFehler(fehler: Throwable): String {
    val ursache = fehler.cause?.message
    return if (ursache != null) {
        "Verbindung fehlgeschlagen: ${fehler.message} ($ursache)"
    } else {
        "Verbindung fehlgeschlagen: ${fehler.message}"
    }
}

/**
 * Ergebnis eines Versuchs, den Drive-Sync-Ordner einzurichten (siehe [versucheDriveEinrichtung]).
 * Eigener Typ statt eines simplen `Result<String>`, weil [ZustimmungNoetig] kein Fehler im
 * ueblichen Sinn ist - der Aufrufer muss stattdessen den Zustimmungsbildschirm anzeigen, siehe
 * [AutorisierungBenoetigtException].
 */
internal sealed interface DriveEinrichtungsVersuch {
    data class Erfolg(val nachricht: String, val folderId: String?) : DriveEinrichtungsVersuch
    data class Fehler(val nachricht: String) : DriveEinrichtungsVersuch
    data class ZustimmungNoetig(val intentSender: IntentSender) : DriveEinrichtungsVersuch
}

/**
 * Owner-Rückmeldung nach Geraetetest: "Mit Google verbinden" scheiterte dauerhaft mit
 * "Verbindung fehlgeschlagen: Kein Zugriffstoken verfügbar (Autorisierung ohne Zugriffstoken -
 * erneute Zustimmung nötig)", weil Google beim allerersten Autorisieren des drive.file-Scopes
 * einen expliziten Zustimmungsbildschirm verlangt (siehe [AutorisierungBenoetigtException]) - die
 * App zeigte dafuer aber nur eine generische Fehlermeldung an, ohne diesen Bildschirm je zu
 * starten. Wird sowohl vom Button-`onClick` als auch nach Rueckkehr aus dem Zustimmungsbildschirm
 * aufgerufen (siehe `driveZustimmungLauncher` in [SettingsScreen]), deshalb als eigene Funktion
 * statt inline im Composable.
 */
internal suspend fun versucheDriveEinrichtung(
    container: AppContainer,
    settings: SettingsManager,
    ordnerName: String,
): DriveEinrichtungsVersuch {
    val name = ordnerName.ifBlank { "Lärmprotokoll" }
    return container.driveEinrichtung.richteEin(name).fold(
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

/**
 * Sucht die komplette `cause`-Kette nach einer [AutorisierungBenoetigtException] ab, statt nur den
 * unmittelbaren `cause` zu pruefen - [com.example.lrmprotokoll.drive.GoogleDriveApiClient]s
 * `mitToken()` verpackt sie bereits einmal in eine `DriveApiException`, und ein zukuenftiger
 * weiterer Wrapper soll diese Pruefung nicht stillschweigend brechen.
 */
internal tailrec fun findeAutorisierungBenoetigt(fehler: Throwable?): AutorisierungBenoetigtException? =
    when (fehler) {
        null -> null
        is AutorisierungBenoetigtException -> fehler
        else -> findeAutorisierungBenoetigt(fehler.cause)
    }
