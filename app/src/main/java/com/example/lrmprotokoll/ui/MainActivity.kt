package com.example.lrmprotokoll.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.audio.AudioRecordingService
import com.example.lrmprotokoll.audio.EXTRA_START_AUDIO_MONITORING
import com.example.lrmprotokoll.audio.NoiseClassifier
import com.example.lrmprotokoll.data.NoiseRecord
import com.example.lrmprotokoll.data.ReferenceSound
import com.example.lrmprotokoll.messreihe.leiteDashboardAnzeigeAb
import com.example.lrmprotokoll.report.ReportManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.io.File

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    NavHost(navController = navController, startDestination = "main") {
                        composable("main") {
                            NoiseProtocolApp(
                                onNavigateToPlayer = { filePath -> navController.navigate("player?path=$filePath") },
                                onNavigateToSettings = { navController.navigate("settings") },
                                onNavigateToMeter = { navController.navigate("meter") },
                                onNavigateToProtokoll = { navController.navigate("protokoll") },
                                onNavigateToDiagnose = { navController.navigate("diagnose") },
                            )
                        }
                        composable(
                            "player?path={path}",
                            arguments = listOf(navArgument("path") { defaultValue = "" })
                        ) { backStackEntry ->
                            val path = backStackEntry.arguments?.getString("path") ?: ""
                            AudioPlayerScreen(filePath = path, onBack = { navController.popBackStack() })
                        }
                        composable("settings") {
                            SettingsScreen(onBack = { navController.popBackStack() })
                        }
                        composable("meter") {
                            MeterScreen(onBack = { navController.popBackStack() })
                        }
                        composable("protokoll") {
                            ProtokollScreen(
                                onBack = { navController.popBackStack() },
                                onOpenSession = { sessionId -> navController.navigate("protokoll/$sessionId") },
                            )
                        }
                        composable(
                            "protokoll/{sessionId}",
                            arguments = listOf(navArgument("sessionId") { type = NavType.LongType })
                        ) { backStackEntry ->
                            val sessionId = backStackEntry.arguments?.getLong("sessionId") ?: 0L
                            ProtokollDetailScreen(sessionId = sessionId, onBack = { navController.popBackStack() })
                        }
                        composable("diagnose") {
                            DiagnoseScreen(onBack = { navController.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoiseProtocolApp(
    onNavigateToPlayer: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToMeter: () -> Unit,
    onNavigateToProtokoll: () -> Unit,
    onNavigateToDiagnose: () -> Unit,
) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as LaermprotokollApp).container }
    val db = container.database
    val dao = db.noiseDao()
    val records by dao.getAll().collectAsState(initial = emptyList())
    val references by dao.getAllReferences().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val reportManager = remember { ReportManager(context) }
    val classifier = remember { NoiseClassifier(context) }
    
    var reportTargetRecords by remember { mutableStateOf<List<NoiseRecord>?>(null) }
    var deleteTargetRecords by remember { mutableStateOf<List<NoiseRecord>?>(null) }
    var showReferenceDialog by remember { mutableStateOf<NoiseRecord?>(null) }
    var refName by remember { mutableStateOf("") }
    
    val selectedIds = remember { mutableStateListOf<Long>() }
    val collapsedDays = remember { mutableStateListOf<String>() }
    val dayFilters = remember { mutableStateMapOf<String, String>() }
    
    var minDbFilter by remember { mutableFloatStateOf(0f) }
    var maxDbFilter by remember { mutableFloatStateOf(120f) }
    var startHour by remember { mutableFloatStateOf(0f) }
    var endHour by remember { mutableFloatStateOf(23f) }
    var showGlobalFilter by remember { mutableStateOf(false) }

    var hasPermissions by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    var hasBluetoothPermissions by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        hasPermissions = permissions[Manifest.permission.RECORD_AUDIO] == true
        hasBluetoothPermissions = permissions[Manifest.permission.BLUETOOTH_SCAN] == true &&
            permissions[Manifest.permission.BLUETOOTH_CONNECT] == true
    }

    LaunchedEffect(Unit) {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissionsToRequest.toTypedArray())
    }

    // M7c Aufgabe 3 (Bestandsaufnahme Verbesserungsvorschlag 3): erkennbare Struktur mit klar
    // benannten Zielen statt verstreuter Text-/Icon-Buttons in der Kopfzeile - Diagnose war zuvor
    // nur ueber ein Info-Icon ohne Text erreichbar (Befund 4, explizit zu beheben). Nur auf dem
    // Home-Screen: die Zielscreens bleiben bei Zurueck-Pfeil + TopAppBar, kein globaler Umbau
    // aller Screens noetig.
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = true,
                    onClick = {},
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("Start") },
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onNavigateToMeter,
                    icon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                    label = { Text("Messgerät") },
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onNavigateToProtokoll,
                    icon = { Icon(Icons.Default.List, contentDescription = null) },
                    label = { Text("Protokoll") },
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onNavigateToDiagnose,
                    icon = { Icon(Icons.Default.Info, contentDescription = null) },
                    label = { Text("Diagnose") },
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onNavigateToSettings,
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Einstellungen") },
                )
            }
        }
    ) { scaffoldPadding ->
    Column(modifier = Modifier.padding(scaffoldPadding).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Lärmprotokoll", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            if (selectedIds.isNotEmpty()) {
                IconButton(onClick = {
                    val targets = records.filter { selectedIds.contains(it.id) }
                    deleteTargetRecords = targets
                }) {
                    Icon(Icons.Default.Delete, contentDescription = "Löschen", tint = MaterialTheme.colorScheme.error)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        ServiceControl(context, hasPermissions)

        Spacer(modifier = Modifier.height(16.dp))
        
        // Globaler Filter
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { showGlobalFilter = !showGlobalFilter }) {
                    Icon(if (showGlobalFilter) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Globale Filter (Pegel)", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.weight(1f))
                    if (minDbFilter > 0 || maxDbFilter < 120) {
                        AssistChip(onClick = {}, label = { Text("${minDbFilter.toInt()}-${maxDbFilter.toInt()} dB") })
                    }
                }
                
                AnimatedVisibility(visible = showGlobalFilter) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        Text("Pegelbereich (dB): ${minDbFilter.toInt()} - ${maxDbFilter.toInt()}")
                        RangeSlider(
                            value = minDbFilter..maxDbFilter,
                            onValueChange = { range ->
                                minDbFilter = range.start
                                maxDbFilter = range.endInclusive
                            },
                            valueRange = 0f..120f,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text("Zeitraum (Uhrzeit): ${startHour.toInt()}:00 - ${endHour.toInt()}:59")
                        RangeSlider(
                            value = startHour..endHour,
                            onValueChange = { range ->
                                startHour = range.start
                                endHour = range.endInclusive
                            },
                            valueRange = 0f..23f,
                            steps = 22,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )

                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = { 
                                minDbFilter = 0f; maxDbFilter = 120f 
                                startHour = 0f; endHour = 23f
                            }) {
                                Text("Filter zurücksetzen")
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Anzeige bekannter Geräusche (Datenbank)
        if (references.isNotEmpty()) {
            Text("Gelernte Geräusche: ${references.size}", style = MaterialTheme.typography.titleSmall)
            FlowRow(modifier = Modifier.fillMaxWidth()) {
                references.forEach { ref ->
                    AssistChip(
                        onClick = { /* Optional: Filter by this reference */ },
                        label = { Text(ref.name) },
                        trailingIcon = {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp).clickable {
                                scope.launch { dao.deleteReference(ref.id) }
                            })
                        },
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        val filteredRecords = remember(records, minDbFilter, maxDbFilter, startHour, endHour) {
            records.filter { 
                val cal = Calendar.getInstance().apply { timeInMillis = it.timestamp }
                val hour = cal.get(Calendar.HOUR_OF_DAY)
                it.dbValue >= minDbFilter && it.dbValue <= maxDbFilter && 
                hour >= startHour.toInt() && hour <= endHour.toInt()
            }
        }

        val groupedRecords = remember(filteredRecords) {
            filteredRecords.groupBy {
                SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(it.timestamp)) 
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            groupedRecords.forEach { (date, dailyRecords) ->
                val currentFilter = dayFilters[date] ?: "Alle"
                val filteredDailyRecords = if (currentFilter == "Alle") dailyRecords 
                                           else dailyRecords.filter { it.label == currentFilter || it.detectedLabel == currentFilter }
                
                val isCollapsed = collapsedDays.contains(date)
                
                item {
                    Column {
                        Surface(
                            onClick = {
                                if (isCollapsed) collapsedDays.remove(date)
                                else collapsedDays.add(date)
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isCollapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                    contentDescription = null
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(date, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                
                                IconButton(onClick = { deleteTargetRecords = filteredDailyRecords }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Gefilterte löschen", tint = MaterialTheme.colorScheme.error)
                                }
                                
                                TextButton(onClick = { reportTargetRecords = filteredDailyRecords }) {
                                    Text("Bericht")
                                }
                            }
                        }
                        
                        if (!isCollapsed) {
                            val allLabels = dailyRecords.flatMap { listOfNotNull(it.label, it.detectedLabel) }.distinct()
                            var expanded by remember { mutableStateOf(false) }
                            
                            Box(modifier = Modifier.padding(start = 32.dp, bottom = 8.dp)) {
                                TextButton(onClick = { expanded = true }) {
                                    Icon(Icons.Default.Menu, contentDescription = null)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Filter: $currentFilter")
                                }
                                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                    DropdownMenuItem(text = { Text("Alle") }, onClick = { dayFilters[date] = "Alle"; expanded = false })
                                    allLabels.forEach { label ->
                                        DropdownMenuItem(text = { Text(label) }, onClick = { dayFilters[date] = label; expanded = false })
                                    }
                                }
                            }
                        }
                    }
                }
                
                if (!isCollapsed) {
                    items(filteredDailyRecords) { record ->
                        NoiseRecordItem(
                            record = record, 
                            isSelected = selectedIds.contains(record.id),
                            onPlay = { onNavigateToPlayer(record.filePath) },
                            onLabel = { label -> scope.launch { dao.update(record.copy(label = label)) } },
                            onDelete = { deleteTargetRecords = listOf(record) },
                            onFavorite = { showReferenceDialog = record },
                            onLongClick = {
                                if (selectedIds.contains(record.id)) selectedIds.remove(record.id)
                                else selectedIds.add(record.id)
                            },
                            onAiRecognize = {
                                scope.launch {
                                    val detected = classifier.classify(File(record.filePath))
                                    dao.update(record.copy(detectedLabel = detected ?: "Nicht erkannt"))
                                }
                            }
                        )
                    }
                }
            }
        }
    }
    }

    if (showReferenceDialog != null) {
        AlertDialog(
            onDismissRequest = { showReferenceDialog = null },
            title = { Text("Geräusch lernen") },
            text = {
                Column {
                    Text("Geben Sie diesem Geräusch-Muster einen Namen für den automatischen Abgleich:")
                    TextField(value = refName, onValueChange = { refName = it })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val record = showReferenceDialog!!
                        val detailed = classifier.classifyDetailed(File(record.filePath))
                        if (detailed != null) {
                            dao.insertReference(ReferenceSound(name = refName, pattern = detailed.joinToString(",")))
                        }
                        showReferenceDialog = null
                        refName = ""
                    }
                }) { Text("Speichern") }
            },
            dismissButton = {
                TextButton(onClick = { showReferenceDialog = null }) { Text("Abbrechen") }
            }
        )
    }

    if (reportTargetRecords != null) {
        AlertDialog(
            onDismissRequest = { reportTargetRecords = null },
            title = { Text("Tagesbericht") },
            text = { Text("Was möchten Sie für diesen Tag (gefiltert) tun?") },
            confirmButton = {
                TextButton(onClick = {
                    val target = reportTargetRecords!!
                    val report = reportManager.generateDailyReport(target)
                    reportManager.createZipAndShare(target, report)
                    reportTargetRecords = null
                }) { Text("Gefilterte teilen (ZIP)") }
            },
            dismissButton = {
                TextButton(onClick = {
                    val report = reportManager.generateDailyReport(reportTargetRecords!!)
                    reportManager.shareFile(report)
                    reportTargetRecords = null
                }) { Text("Nur Bericht teilen") }
            }
        )
    }

    if (deleteTargetRecords != null) {
        AlertDialog(
            onDismissRequest = { deleteTargetRecords = null },
            title = { Text("Löschen bestätigen") },
            text = { Text("Möchten Sie die ausgewählten Aufnahmen (${deleteTargetRecords!!.size}) wirklich unwiderruflich löschen?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val targets = deleteTargetRecords!!
                            targets.forEach { record ->
                                val file = File(record.filePath)
                                if (file.exists()) file.delete()
                            }
                            dao.deleteMultiple(targets.map { it.id })
                            selectedIds.clear()
                            deleteTargetRecords = null
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Löschen") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTargetRecords = null }) { Text("Abbrechen") }
            }
        )
    }
}

/**
 * Live-Status-Dashboard (M7c Aufgabe 1, Bestandsaufnahme-Befund 4.1/Verbesserungsvorschlag 1):
 * ersetzt den frueheren einmaligen ActivityManager.getRunningServices()-Poll durch echte,
 * beobachtete Zustaende - [AudioRecordingService.laeuft] (StateFlow), den
 * Messgeraet-Verbindungszustand und den Live-Pegel. Reine Anzeigelogik steht in
 * [leiteDashboardAnzeigeAb] und ist dort ohne Emulator per JVM-Test geprueft; hier nur die
 * Zusammensetzung der Quellen und die Live-Uhr fuer die Laufzeit.
 */
@Composable
fun ServiceControl(context: Context, hasPermissions: Boolean) {
    val container = remember { (context.applicationContext as LaermprotokollApp).container }

    val dienstAktiv by AudioRecordingService.laeuft.collectAsState()
    val verbindungszustand by container.connectionSupervisor.state.collectAsState()
    val letzterFrame by container.meterTransport.frames.collectAsState(initial = null)
    val geraetGepinnt = container.settingsManager.meterDeviceAddress != null

    // Nicht live per Flow (SessionDao liefert keinen), aber bei jedem Verbindungswechsel neu
    // geladen - eine Session beginnt/endet genau dann, wenn sich verbindungszustand aendert
    // (MeasurementRecorder.onState), das haelt den Wert praktisch aktuell.
    var sessionStartedAt by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(verbindungszustand, geraetGepinnt) {
        sessionStartedAt = if (geraetGepinnt) container.database.sessionDao().offeneSession()?.startedAt else null
    }

    var jetzt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sessionStartedAt) {
        while (sessionStartedAt != null) {
            jetzt = System.currentTimeMillis()
            delay(1000)
        }
    }

    val anzeige = leiteDashboardAnzeigeAb(
        dienstAktiv = dienstAktiv,
        geraetGepinnt = geraetGepinnt,
        verbindungszustand = verbindungszustand,
        sessionStartedAtMillis = sessionStartedAt,
        jetztMillis = jetzt,
        letzterPegel = letzterFrame?.level,
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Überwachung:", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.width(8.dp))

                if (anzeige.dienstAktiv) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color.Red)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("AKTIV", color = Color.Red, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text("Inaktiv", style = MaterialTheme.typography.bodySmall)
                }
            }

            if (anzeige.dienstAktiv) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(anzeige.betriebsartText, style = MaterialTheme.typography.bodyMedium)
                anzeige.laufzeitText?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                anzeige.pegelText?.let {
                    Text(it, style = MaterialTheme.typography.bodyLarge)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        if (!hasPermissions) {
                            Toast.makeText(context, "Berechtigung erforderlich", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        val intent = Intent(context, AudioRecordingService::class.java).apply {
                            putExtra(EXTRA_START_AUDIO_MONITORING, true)
                        }
                        context.startForegroundService(intent)
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !anzeige.dienstAktiv,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) {
                    Text("Aufnahme starten")
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = {
                        val intent = Intent(context, AudioRecordingService::class.java).apply {
                            action = "STOP_SERVICE"
                        }
                        context.startService(intent)
                    },
                    modifier = Modifier.weight(1f),
                    enabled = anzeige.dienstAktiv,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Aufnahme beenden")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun NoiseRecordItem(
    record: NoiseRecord, 
    isSelected: Boolean, 
    onPlay: () -> Unit, 
    onLabel: (String) -> Unit, 
    onDelete: () -> Unit,
    onFavorite: () -> Unit,
    onLongClick: () -> Unit,
    onAiRecognize: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .combinedClickable(
                onClick = onPlay,
                onLongClick = onLongClick
            ),
        colors = if (isSelected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) 
                 else CardDefaults.cardColors()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(record.timestamp))
                    Text(time)
                    Text("Pegel: ${String.format("%.1f", record.dbValue)} dB (Amp: ${record.amplitude.toInt()})", style = MaterialTheme.typography.bodySmall)
                    if (record.detectedLabel != null) {
                        Text("KI Erkannt: ${record.detectedLabel}", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
                    }
                    if (record.label != null) {
                        Text("Nutzer Label: ${record.label}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                
                IconButton(onClick = onAiRecognize) {
                    Icon(Icons.Default.Refresh, contentDescription = "AI Recognition", tint = MaterialTheme.colorScheme.tertiary)
                }
                
                IconButton(onClick = onFavorite) {
                    Icon(Icons.Default.Star, contentDescription = "Lernen", tint = Color(0xFFFFB300))
                }

                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Löschen", tint = MaterialTheme.colorScheme.error)
                }

                IconButton(onClick = onPlay) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Abspielen")
                }
            }
            FlowRow(modifier = Modifier.fillMaxWidth()) {
                listOf("Bagger", "Bohren", "Hämmern", "Verkehr").forEach { label ->
                    AssistChip(
                        onClick = { onLabel(label) },
                        label = { Text(label) },
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
            }
        }
    }
}
