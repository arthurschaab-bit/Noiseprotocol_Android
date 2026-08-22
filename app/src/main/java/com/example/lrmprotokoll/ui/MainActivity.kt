package com.example.lrmprotokoll.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.audio.ACTION_START_AUDIO_MONITORING
import com.example.lrmprotokoll.audio.ACTION_STOP_AUDIO_RECORDING
import com.example.lrmprotokoll.audio.ACTION_STOP_SERVICE
import com.example.lrmprotokoll.audio.AudioRecordingService
import com.example.lrmprotokoll.audio.EXTRA_START_AUDIO_MONITORING
import com.example.lrmprotokoll.audio.NoiseClassifier
import com.example.lrmprotokoll.data.MeasurementEntity
import com.example.lrmprotokoll.data.MinuteAggregateEntity
import com.example.lrmprotokoll.ui.components.NoiseCard
import com.example.lrmprotokoll.ui.components.StatusPill
import com.example.lrmprotokoll.ui.components.StatusPillType
import com.example.lrmprotokoll.data.NoiseRecord
import com.example.lrmprotokoll.data.ReferenceSound
import com.example.lrmprotokoll.diagnose.HealthStatus
import com.example.lrmprotokoll.diagnose.SystemHealthParams
import com.example.lrmprotokoll.diagnose.bewerteSystemZustand
import com.example.lrmprotokoll.messreihe.*
import com.example.lrmprotokoll.report.ReportManager
import com.example.lrmprotokoll.ui.theme.LaermprotokollTheme
import com.example.lrmprotokoll.ui.theme.statusColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LaermprotokollTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation()
                }
            }
        }
    }
}

/**
 * Zentrale Navigation mit NavigationDrawer (☰), BottomBar und globalem SnackbarHost.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(navController: NavHostController = rememberNavController()) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as LaermprotokollApp).container }
    val settingsManager = container.settingsManager
    var onboardingDone by remember { mutableStateOf(settingsManager.onboardingCompleted) }

    if (!onboardingDone) {
        OnboardingScreen(onFinish = {
            settingsManager.onboardingCompleted = true
            onboardingDone = true
        })
        return
    }

    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun navigiereZuTab(route: String) {
        navController.navigate(route) {
            popUpTo("main") { inclusive = route == "main" }
            launchSingleTop = true
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AppDrawerContent(
                currentRoute = currentRoute,
                onNavigate = { route -> navigiereZuTab(route) },
                onCloseDrawer = { scope.launch { drawerState.close() } }
            )
        }
    ) {
        val showBottomNav = currentRoute == null || currentRoute in listOf("main", "meter", "protokoll", "diagnose", "settings")

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (showBottomNav) {
                    AppNavigationBar(
                        currentRoute = currentRoute,
                        onNavigateToStart = { navigiereZuTab("main") },
                        onNavigateToProtokoll = { navigiereZuTab("protokoll") },
                        onNavigateToDiagnose = { navigiereZuTab("diagnose") },
                        onNavigateToSettings = { navigiereZuTab("settings") },
                    )
                }
            }
        ) { scaffoldPadding ->
            NavHost(
                navController = navController,
                startDestination = "main",
                modifier = Modifier.padding(scaffoldPadding),
            ) {
                composable("main") {
                    NoiseProtocolApp(
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onNavigateToPlayer = { filePath -> navController.navigate("player?path=$filePath") },
                        onNavigateToSettings = { navigiereZuTab("settings") },
                        onNavigateToMeter = { navigiereZuTab("meter") },
                        onNavigateToProtokoll = { navigiereZuTab("protokoll") },
                        onNavigateToDiagnose = { navigiereZuTab("diagnose") },
                        onShowSnackbar = { msg, action, onAction ->
                            scope.launch {
                                val result = snackbarHostState.showSnackbar(
                                    message = msg,
                                    actionLabel = action,
                                    duration = SnackbarDuration.Short
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    onAction?.invoke()
                                }
                            }
                        }
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
                    SettingsScreen(
                        onBack = { navController.popBackStack() },
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onShowSnackbar = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }
                    )
                }
                composable("meter") {
                    MeterScreen(
                        onBack = { navController.popBackStack() },
                        onOpenDrawer = { scope.launch { drawerState.open() } }
                    )
                }
                composable("protokoll") {
                    ProtokollScreen(
                        onBack = { navController.popBackStack() },
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onOpenSession = { sessionId -> navController.navigate("protokoll/$sessionId") },
                    )
                }
                composable(
                    "protokoll/{sessionId}",
                    arguments = listOf(navArgument("sessionId") { type = NavType.LongType })
                ) { backStackEntry ->
                    val sessionId = backStackEntry.arguments?.getLong("sessionId") ?: 0L
                    ProtokollDetailScreen(
                        sessionId = sessionId,
                        onBack = { navController.popBackStack() },
                        onShowSnackbar = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }
                    )
                }
                composable("diagnose") {
                    DiagnoseScreen(
                        onBack = { navController.popBackStack() },
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onShowSnackbar = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }
                    )
                }
                composable("trash") {
                    TrashScreen(
                        onNavigateBack = { navController.popBackStack() },
                        onShowSnackbar = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }
                    )
                }
            }
        }
    }
}

/**
 * Bottom Navigation Bar mit semantischen Icons für 4 Hauptnavigationsziele.
 */
@Composable
fun AppNavigationBar(
    currentRoute: String?,
    onNavigateToStart: () -> Unit,
    onNavigateToProtokoll: () -> Unit,
    onNavigateToDiagnose: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToMeter: (() -> Unit)? = null,
) {
    NavigationBar {
        NavigationBarItem(
            selected = istBottomNavZielAktiv(currentRoute, "main"),
            onClick = onNavigateToStart,
            icon = { Icon(Icons.Default.Home, contentDescription = stringResource(R.string.nav_start)) },
            label = { Text(stringResource(R.string.nav_start)) },
            modifier = Modifier.heightIn(min = 48.dp)
        )
        NavigationBarItem(
            selected = istBottomNavZielAktiv(currentRoute, "protokoll"),
            onClick = onNavigateToProtokoll,
            icon = { Icon(AppIcons.BarChart, contentDescription = stringResource(R.string.nav_protocol)) },
            label = { Text(stringResource(R.string.nav_protocol)) },
            modifier = Modifier.heightIn(min = 48.dp)
        )
        NavigationBarItem(
            selected = istBottomNavZielAktiv(currentRoute, "diagnose"),
            onClick = onNavigateToDiagnose,
            icon = { Icon(AppIcons.Diagnose, contentDescription = stringResource(R.string.nav_diagnose)) },
            label = { Text(stringResource(R.string.nav_diagnose)) },
            modifier = Modifier.heightIn(min = 48.dp)
        )
        NavigationBarItem(
            selected = istBottomNavZielAktiv(currentRoute, "settings"),
            onClick = onNavigateToSettings,
            icon = { Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.nav_settings)) },
            label = { Text(stringResource(R.string.nav_settings)) },
            modifier = Modifier.heightIn(min = 48.dp)
        )
    }
}

internal fun istBottomNavZielAktiv(currentRoute: String?, ziel: String): Boolean =
    currentRoute == ziel || currentRoute?.startsWith("$ziel/") == true

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun NoiseProtocolApp(
    onOpenDrawer: () -> Unit,
    onNavigateToPlayer: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToMeter: () -> Unit,
    onNavigateToProtokoll: () -> Unit,
    onNavigateToDiagnose: () -> Unit,
    onShowSnackbar: (String, String?, (() -> Unit)?) -> Unit = { _, _, _ -> },
) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as LaermprotokollApp).container }
    val settingsManager = container.settingsManager
    val db = container.database
    val dao = db.noiseDao()
    val records by dao.getAll().collectAsState(initial = emptyList())
    val references by dao.getAllReferences().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val reportManager = remember { ReportManager(context) }
    val classifier = remember { NoiseClassifier(context) }

    var reportTargetRecords by remember { mutableStateOf<List<NoiseRecord>?>(null) }
    var referenceToDelete by remember { mutableStateOf<ReferenceSound?>(null) }
    var showReferenceDialog by remember { mutableStateOf<NoiseRecord?>(null) }
    var showPairingDialog by remember { mutableStateOf(false) }
    val letzteSession by db.sessionDao().letzteSessionFlow().collectAsState(initial = null)
    val latestFrame by container.meterTransport.frames.collectAsState(initial = null)
    var refName by remember { mutableStateOf("") }
    var showOverflowMenu by remember { mutableStateOf(false) }

    val selectedIds = remember { mutableStateListOf<Long>() }
    val collapsedDays = remember { mutableStateListOf<String>() }

    // F2: Persistenter Filter-State
    var filterState by remember {
        mutableStateOf(
            RecordFilterState(
                query = settingsManager.filterSearchQuery,
                minDb = settingsManager.filterDbMin,
                maxDb = settingsManager.filterDbMax,
                onlyMeter = settingsManager.filterOnlyMeter,
                onlyCalibrated = settingsManager.filterOnlyCalibrated,
                onlyFavorites = settingsManager.filterOnlyFavorites,
                onlyQuietHours = settingsManager.filterOnlyQuietHours
            )
        )
    }
    var showFilterPanel by remember { mutableStateOf(false) }

    fun updateFilter(newFilter: RecordFilterState) {
        filterState = newFilter
        settingsManager.filterSearchQuery = newFilter.query
        settingsManager.filterDbMin = newFilter.minDb
        settingsManager.filterDbMax = newFilter.maxDb
        settingsManager.filterOnlyMeter = newFilter.onlyMeter
        settingsManager.filterOnlyCalibrated = newFilter.onlyCalibrated
        settingsManager.filterOnlyFavorites = newFilter.onlyFavorites
        settingsManager.filterOnlyQuietHours = newFilter.onlyQuietHours
    }

    // Lifecycle-Überwachung für Berechtigungs-Aktualisierung bei Rückkehr
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasAudioPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAudioPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                } else true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // F3: System-Selbstprüfung für Warn-Banner
    val dienstAktiv by AudioRecordingService.laeuft.collectAsState()
    val verbindungszustand by container.connectionSupervisor.state.collectAsState()
    val isBatteryOptimizationIgnored = remember {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    }
    val healthOverview = remember(hasAudioPermission, hasNotificationPermission, dienstAktiv, verbindungszustand) {
        bewerteSystemZustand(
            SystemHealthParams(
                hasAudioPermission = hasAudioPermission,
                hasNotificationPermission = hasNotificationPermission,
                hasBluetoothPermission = true,
                isBatteryOptimizationIgnored = isBatteryOptimizationIgnored,
                canScheduleExactAlarms = true,
                isBluetoothAdapterEnabled = true,
                isMeterPinned = settingsManager.meterDeviceAddress != null,
                meterConnectionState = verbindungszustand,
                isAlertingConfigured = settingsManager.alarmierungAktiv,
                isDriveSyncConfigured = settingsManager.driveSyncEnabled,
                isDiagnoseLoggingActive = settingsManager.diagnoseLoggingAktiv,
                isMonitoringActive = dienstAktiv
            )
        )
    }

    // Single LazyColumn Layout für die gesamte Startseite
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        // 1. TopAppBar als Listeneintrag (integriert, kein Nested Scroll Konflikt)
        item {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onOpenDrawer,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.action_menu))
                    }
                },
                actions = {
                    BluetoothStatusBadge(
                        state = verbindungszustand,
                        deviceName = settingsManager.meterDeviceName,
                        onClick = { showPairingDialog = true },
                        modifier = Modifier.padding(end = 4.dp)
                    )

                    Box {
                        IconButton(
                            onClick = { showOverflowMenu = true },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_options))
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (showFilterPanel) "Filter ausblenden" else "Filter & Suche") },
                                leadingIcon = { Icon(AppIcons.FilterList, contentDescription = null) },
                                onClick = {
                                    showFilterPanel = !showFilterPanel
                                    showOverflowMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("KI-Batch-Erkennung") },
                                leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                onClick = {
                                    showOverflowMenu = false
                                    scope.launch {
                                        var count = 0
                                        records.filter { it.detectedLabel == null }.forEach { r ->
                                            val detected = classifier.classify(File(r.filePath))
                                            if (detected != null) {
                                                dao.update(r.copy(detectedLabel = detected))
                                                count++
                                            }
                                        }
                                        onShowSnackbar("$count Aufnahme(n) durch KI klassifiziert", null, null)
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Tagesbericht exportieren") },
                                leadingIcon = { Icon(AppIcons.BarChart, contentDescription = null) },
                                onClick = {
                                    showOverflowMenu = false
                                    if (records.isNotEmpty()) {
                                        reportTargetRecords = records
                                    } else {
                                        onShowSnackbar("Keine Aufnahmen vorhanden", null, null)
                                    }
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.nav_settings)) },
                                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                onClick = {
                                    showOverflowMenu = false
                                    onNavigateToSettings()
                                }
                            )
                        }
                    }
                }
            )
        }

        // 2. F3: Problem-Banner bei aktiver Überwachung
        if (healthOverview.hasProblemWhileMonitoring) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Einschränkung bei Überwachung",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = "Mindestens eine erforderliche Berechtigung fehlt.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        TextButton(onClick = onNavigateToDiagnose) {
                            Text("Prüfen", color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // 3. Live-Cockpit (1-Sekunden Header, Live-Kurve, Messung starten/stoppen, Quick-Tagger)
        item {
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                LiveCockpitCard(
                    onShowSnackbar = { msg -> onShowSnackbar(msg, null, null) }
                )
            }
        }

        // 4. PCE-323 Messgerät Steuerung & Kopplung (direkt auf der Startseite)
        item {
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                MeterControlCard(
                    connectionState = verbindungszustand,
                    pairedAddress = settingsManager.meterDeviceAddress,
                    pairedName = settingsManager.meterDeviceName,
                    latestFrame = latestFrame,
                    onConnect = {
                        val intent = Intent(context, AudioRecordingService::class.java)
                        context.startForegroundService(intent)
                    },
                    onDisconnect = {
                        container.connectionSupervisor.stop()
                    },
                    onOpenPairing = {
                        showPairingDialog = true
                    }
                )
            }
        }

        // 5. Dauermessungs-Zusammenfassung (Session-Status & Protokoll-Link)
        if (letzteSession != null) {
            val s = letzteSession
            val isActive = s?.endedAt == null
            item {
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = AppIcons.BarChart,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (isActive) "Dauermessung aktiv" else "Letzte Dauermessung",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                TextButton(onClick = onNavigateToProtokoll) {
                                    Text("Im Protokoll ansehen")
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isActive) {
                                    "Die Pegelwerte werden kontinuierlich erfasst und im Protokoll gespeichert."
                                } else {
                                    "Dauermessung abgeschlossen. Alle 5s-Mittelwerte sind im Protokoll archiviert."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // 4. F2: Filter & Suche Panel
        item {
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showFilterPanel = !showFilterPanel }
                        ) {
                            Icon(
                                imageVector = if (showFilterPanel) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Suche & Filter", style = MaterialTheme.typography.titleSmall)
                            Spacer(modifier = Modifier.weight(1f))
                            if (filterState.istAktiv) {
                                FilterChip(
                                    selected = true,
                                    onClick = { updateFilter(RecordFilterState()) },
                                    label = { Text("Aktiv (Zurücksetzen)") },
                                    trailingIcon = { Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                )
                            }
                        }

                        AnimatedVisibility(visible = showFilterPanel) {
                            Column(modifier = Modifier.padding(top = 12.dp)) {
                                OutlinedTextField(
                                    value = filterState.query,
                                    onValueChange = { updateFilter(filterState.copy(query = it)) },
                                    label = { Text("Suchen (Label, Notiz, KI)") },
                                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                    trailingIcon = {
                                        if (filterState.query.isNotEmpty()) {
                                            IconButton(onClick = { updateFilter(filterState.copy(query = "")) }) {
                                                Icon(Icons.Default.Close, contentDescription = null)
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )

                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Pegelbereich: ${filterState.minDb.toInt()} - ${filterState.maxDb.toInt()} dB",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                RangeSlider(
                                    value = filterState.minDb..filterState.maxDb,
                                    onValueChange = { range ->
                                        updateFilter(filterState.copy(minDb = range.start, maxDb = range.endInclusive))
                                    },
                                    valueRange = 0f..120f,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )

                                Spacer(modifier = Modifier.height(8.dp))
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    FilterChip(
                                        selected = filterState.onlyFavorites,
                                        onClick = { updateFilter(filterState.copy(onlyFavorites = !filterState.onlyFavorites)) },
                                        label = { Text("Favoriten") },
                                        leadingIcon = { Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                    )
                                    FilterChip(
                                        selected = filterState.onlyQuietHours,
                                        onClick = { updateFilter(filterState.copy(onlyQuietHours = !filterState.onlyQuietHours)) },
                                        label = { Text("Ruhezeiten") }
                                    )
                                    FilterChip(
                                        selected = filterState.onlyMeter,
                                        onClick = { updateFilter(filterState.copy(onlyMeter = !filterState.onlyMeter)) },
                                        label = { Text("Nur PCE-323") }
                                    )
                                    FilterChip(
                                        selected = filterState.onlyCalibrated,
                                        onClick = { updateFilter(filterState.copy(onlyCalibrated = !filterState.onlyCalibrated)) },
                                        label = { Text("Nur Kalibriert") }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 5. Gelernte Referenz-Geräusche
        if (references.isNotEmpty()) {
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = "Gelernte Geräusch-Muster (${references.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        references.forEach { ref ->
                            InputChip(
                                selected = false,
                                onClick = { updateFilter(filterState.copy(query = ref.name)) },
                                label = { Text(ref.name) },
                                trailingIcon = {
                                    IconButton(
                                        onClick = { referenceToDelete = ref },
                                        modifier = Modifier.size(20.dp)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Löschen", modifier = Modifier.size(16.dp))
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        // 6. Gefilterte Aufnahmen
        val filteredRecords = filtereNoiseRecords(records, filterState)

        if (records.isEmpty()) {
            // Leerzustand: Noch gar keine Aufnahmen
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.empty_records_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.empty_records_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else if (filteredRecords.isEmpty()) {
            // Leerzustand: Weggefiltert
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = AppIcons.FilterList,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.empty_filtered_records_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.empty_filtered_records_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = { updateFilter(RecordFilterState()) }) {
                            Text(stringResource(R.string.action_reset_filter))
                        }
                    }
                }
            }
        } else {
            val groupedRecords = filteredRecords.groupBy {
                SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(it.timestamp))
            }

            groupedRecords.forEach { (date, dailyRecords) ->
                val isCollapsed = collapsedDays.contains(date)

                item {
                    Surface(
                        onClick = {
                            if (isCollapsed) collapsedDays.remove(date) else collapsedDays.add(date)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isCollapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = date,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${dailyRecords.size} Aufnahmen",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            IconButton(
                                onClick = { reportTargetRecords = dailyRecords },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(AppIcons.BarChart, contentDescription = "Bericht für Tag erstellen")
                            }
                        }
                    }
                }

                if (!isCollapsed) {
                    items(dailyRecords, key = { it.id }) { record ->
                        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                            NoiseRecordItem(
                                record = record,
                                isSelected = selectedIds.contains(record.id),
                                onPlay = { onNavigateToPlayer(record.filePath) },
                                onLabel = { label -> scope.launch { dao.update(record.copy(label = label)) } },
                                onToggleFavorite = {
                                    scope.launch {
                                        dao.setFavorite(record.id, !record.favorite)
                                    }
                                },
                                onDelete = {
                                    scope.launch {
                                        dao.softDelete(record.id)
                                        onShowSnackbar("Aufnahme in Papierkorb verschoben", "RÜCKGÄNGIG") {
                                            scope.launch { dao.restore(record.id) }
                                        }
                                    }
                                },
                                onLearn = { showReferenceDialog = record },
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

    // Dialog: Geräusch lernen
    if (showReferenceDialog != null) {
        AlertDialog(
            onDismissRequest = { showReferenceDialog = null },
            title = { Text("Geräusch lernen") },
            text = {
                Column {
                    Text("Geben Sie diesem Geräusch-Muster einen Namen für den automatischen KI-Abgleich:")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = refName,
                        onValueChange = { refName = it },
                        label = { Text("Name (z.B. Kompressor)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val record = showReferenceDialog ?: return@Button
                        scope.launch {
                            val detailed = classifier.classifyDetailed(File(record.filePath))
                            if (detailed != null && refName.isNotBlank()) {
                                dao.insertReference(ReferenceSound(name = refName.trim(), pattern = detailed.joinToString(",")))
                                onShowSnackbar("Muster '$refName' erfolgreich gelernt", null, null)
                            }
                            showReferenceDialog = null
                            refName = ""
                        }
                    },
                    enabled = refName.isNotBlank()
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showReferenceDialog = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Dialog: Referenzgeräusch löschen Bestätigung
    referenceToDelete?.let { ref ->
        AlertDialog(
            onDismissRequest = { referenceToDelete = null },
            title = { Text("Muster löschen?") },
            text = { Text("Möchten Sie das gelernte Muster '${ref.name}' wirklich entfernen?") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            dao.deleteReference(ref.id)
                            referenceToDelete = null
                            onShowSnackbar("Muster '${ref.name}' gelöscht", null, null)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { referenceToDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Dialog: Tagesbericht
    reportTargetRecords?.let { target ->
        AlertDialog(
            onDismissRequest = { reportTargetRecords = null },
            title = { Text("Tagesbericht exportieren") },
            text = { Text("Möchten Sie den Textbericht oder ein ZIP-Paket inkl. Audioaufnahmen (${target.size} Dateien) teilen?") },
            confirmButton = {
                Button(onClick = {
                    val report = reportManager.generateDailyReport(target)
                    reportManager.createZipAndShare(target, report)
                    reportTargetRecords = null
                }) {
                    Text("ZIP inkl. Audio teilen")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    val report = reportManager.generateDailyReport(target)
                    reportManager.shareFile(report)
                    reportTargetRecords = null
                }) {
                    Text("Nur Textbericht teilen")
                }
            }
        )
    }

    // Dialog: PCE-323 Kopplung
    if (showPairingDialog) {
        MeterPairingDialog(
            pairedAddress = settingsManager.meterDeviceAddress,
            pairedName = settingsManager.meterDeviceName,
            onDeviceSelected = { device ->
                settingsManager.meterDeviceAddress = device.address
                settingsManager.meterDeviceName = device.name
                showPairingDialog = false
                val intent = Intent(context, AudioRecordingService::class.java)
                context.startForegroundService(intent)
                onShowSnackbar("PCE-323 (${device.name ?: device.address}) gekoppelt", null, null)
            },
            onDismiss = { showPairingDialog = false }
        )
    }
}

/**
 * NoiseRecordItem: Hebt kalibrierte Werte und Triggerquelle prominent hervor (M9 Aufgabe 9 & UX-Redesign).
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun NoiseRecordItem(
    record: NoiseRecord,
    isSelected: Boolean,
    onPlay: () -> Unit,
    onLabel: (String) -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit,
    onLearn: () -> Unit,
    onLongClick: () -> Unit,
    onAiRecognize: () -> Unit
) {
    NoiseCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .combinedClickable(
                onClick = onPlay,
                onLongClick = onLongClick
            ),
        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(record.timestamp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(time, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    if (record.isQuietHour) {
                        Spacer(modifier = Modifier.width(6.dp))
                        StatusPill(text = "Ruhezeit", type = StatusPillType.WARNING)
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                // Prominente Anzeige des kalibrierten vs. unkalibrierten Werts
                if (record.calibratedDbA != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${String.format(Locale.getDefault(), "%.1f", record.calibratedDbA)} dBA",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "(PCE-323 · Mikrofon: ${String.format(Locale.getDefault(), "%.1f", record.dbValue)} dB)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Text(
                        text = "${String.format(Locale.getDefault(), "%.1f", record.dbValue)} dB (Mikrofon)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (record.detectedLabel != null) {
                    Text(
                        text = "KI: ${record.detectedLabel}",
                        color = MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (record.label != null) {
                    Text(
                        text = "Label: ${record.label}",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = "Favorit",
                    tint = if (record.favorite) Color(0xFFFFB300) else MaterialTheme.colorScheme.outline
                )
            }

            IconButton(
                onClick = onAiRecognize,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "KI-Erkennung", tint = MaterialTheme.colorScheme.secondary)
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error)
            }

            IconButton(
                onClick = onPlay,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Abspielen", tint = MaterialTheme.colorScheme.primary)
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf("Bagger", "Bohren", "Hämmern", "Verkehr").forEach { label ->
                AssistChip(
                    onClick = { onLabel(label) },
                    label = { Text(label) },
                    modifier = Modifier.height(28.dp)
                )
            }
            AssistChip(
                onClick = onLearn,
                label = { Text("+ Muster lernen") },
                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp)) },
                modifier = Modifier.height(28.dp)
            )
        }
    }
}
