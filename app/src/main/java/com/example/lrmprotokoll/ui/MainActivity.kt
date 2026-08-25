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
        val container = (application as LaermprotokollApp).container
        val language = container.settingsManager.appLanguage
        if (language.isNotBlank()) {
            val appLocale = androidx.core.os.LocaleListCompat.forLanguageTags(language)
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(appLocale)
        }
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
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
                modifier = Modifier.padding(bottom = scaffoldPadding.calculateBottomPadding()),
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
                        onShowSnackbar = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                        onNavigateToDiagnose = { navController.navigate("diagnose") }
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
                        onStartNewMeasurement = { navigiereZuTab("main") }
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
    onNavigateToSettings: () -> Unit,
    onNavigateToDiagnose: (() -> Unit)? = null,
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

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        hasAudioPermission = perms[Manifest.permission.RECORD_AUDIO] == true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasNotificationPermission = perms[Manifest.permission.POST_NOTIFICATIONS] == true
        }
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
                    MicrophoneStatusBadge(
                        audioMonitoringActive = dienstAktiv,
                        recordWavAudio = settingsManager.recordWavAudio,
                        onClick = {
                            if (dienstAktiv) {
                                val intent = Intent(context, AudioRecordingService::class.java).apply {
                                    action = ACTION_STOP_AUDIO_RECORDING
                                }
                                context.startService(intent)
                            } else {
                                if (hasAudioPermission) {
                                    val intent = Intent(context, AudioRecordingService::class.java).apply {
                                        putExtra(EXTRA_START_AUDIO_MONITORING, true)
                                    }
                                    context.startForegroundService(intent)
                                } else {
                                    permissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.RECORD_AUDIO,
                                            Manifest.permission.POST_NOTIFICATIONS
                                        )
                                    )
                                }
                            }
                        },
                        modifier = Modifier.padding(end = 6.dp)
                    )

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
                                text = { Text(if (showFilterPanel) stringResource(R.string.filter_hide) else stringResource(R.string.filter_title)) },
                                leadingIcon = { Icon(AppIcons.FilterList, contentDescription = null) },
                                onClick = {
                                    showFilterPanel = !showFilterPanel
                                    showOverflowMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_ai_batch)) },
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
                                        onShowSnackbar(context.getString(R.string.ai_classified_count, count), null, null)
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.protocol_daily_report_title)) },
                                leadingIcon = { Icon(AppIcons.BarChart, contentDescription = null) },
                                onClick = {
                                    showOverflowMenu = false
                                    if (records.isNotEmpty()) {
                                        reportTargetRecords = records
                                    } else {
                                        onShowSnackbar(context.getString(R.string.empty_records_title), null, null)
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
                                text = stringResource(R.string.monitoring_restriction_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = stringResource(R.string.monitoring_restriction_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        TextButton(onClick = onNavigateToDiagnose) {
                            Text(stringResource(R.string.action_check), color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // 3. Haupt-Cockpit (Unified Startseite nach neuem Redesign)
        item {
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                LiveCockpitCard(
                    onNavigateToSettings = onNavigateToSettings,
                    onNavigateToDiagnose = onNavigateToDiagnose,
                    onNavigateToMeter = onNavigateToMeter,
                    onShowSnackbar = { msg -> onShowSnackbar(msg, null, null) }
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
                                        text = if (isActive) stringResource(R.string.session_continuous_active) else stringResource(R.string.session_continuous_last),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                TextButton(onClick = onNavigateToProtokoll) {
                                    Text(stringResource(R.string.session_view_in_protocol))
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isActive) {
                                    stringResource(R.string.session_continuous_active_desc)
                                } else {
                                    stringResource(R.string.session_continuous_last_desc)
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
                            Text(stringResource(R.string.filter_title), style = MaterialTheme.typography.titleSmall)
                            Spacer(modifier = Modifier.weight(1f))
                            if (filterState.istAktiv) {
                                FilterChip(
                                    selected = true,
                                    onClick = { updateFilter(RecordFilterState()) },
                                    label = { Text(stringResource(R.string.filter_active_reset)) },
                                    trailingIcon = { Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                )
                            }
                        }

                        AnimatedVisibility(visible = showFilterPanel) {
                            Column(modifier = Modifier.padding(top = 12.dp)) {
                                OutlinedTextField(
                                    value = filterState.query,
                                    onValueChange = { updateFilter(filterState.copy(query = it)) },
                                    label = { Text(stringResource(R.string.filter_search_label)) },
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
                                    text = stringResource(R.string.filter_level_range, filterState.minDb.toInt(), filterState.maxDb.toInt()),
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
                                        label = { Text(stringResource(R.string.filter_favorites)) },
                                        leadingIcon = { Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                    )
                                    FilterChip(
                                        selected = filterState.onlyQuietHours,
                                        onClick = { updateFilter(filterState.copy(onlyQuietHours = !filterState.onlyQuietHours)) },
                                        label = { Text(stringResource(R.string.filter_quiet_hours)) }
                                    )
                                    FilterChip(
                                        selected = filterState.onlyMeter,
                                        onClick = { updateFilter(filterState.copy(onlyMeter = !filterState.onlyMeter)) },
                                        label = { Text(stringResource(R.string.filter_only_meter)) }
                                    )
                                    FilterChip(
                                        selected = filterState.onlyCalibrated,
                                        onClick = { updateFilter(filterState.copy(onlyCalibrated = !filterState.onlyCalibrated)) },
                                        label = { Text(stringResource(R.string.filter_only_calibrated)) }
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
                        text = stringResource(R.string.learned_patterns_count, references.size),
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
                                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_delete), modifier = Modifier.size(16.dp))
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
                                text = stringResource(R.string.protocol_records_count, dailyRecords.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            IconButton(
                                onClick = { reportTargetRecords = dailyRecords },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(AppIcons.BarChart, contentDescription = stringResource(R.string.protocol_daily_report_title))
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
                                        onShowSnackbar(context.getString(R.string.record_moved_to_trash), context.getString(R.string.action_undo)) {
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
                                        dao.update(record.copy(detectedLabel = detected ?: context.getString(R.string.status_not_recognized)))
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
            title = { Text(stringResource(R.string.learn_pattern_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.learn_pattern_desc))
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = refName,
                        onValueChange = { refName = it },
                        label = { Text(stringResource(R.string.learn_pattern_label)) },
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
                                onShowSnackbar(context.getString(R.string.learn_pattern_success, refName), null, null)
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
            title = { Text(stringResource(R.string.delete_pattern_title)) },
            text = { Text(stringResource(R.string.delete_pattern_desc, ref.name)) },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            dao.deleteReference(ref.id)
                            referenceToDelete = null
                            onShowSnackbar(context.getString(R.string.delete_pattern_success, ref.name), null, null)
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
            title = { Text(stringResource(R.string.protocol_daily_report_title)) },
            text = { Text(stringResource(R.string.report_dialog_desc, target.size)) },
            confirmButton = {
                Button(onClick = {
                    val report = reportManager.generateDailyReport(target)
                    reportManager.createZipAndShare(target, report)
                    reportTargetRecords = null
                }) {
                    Text(stringResource(R.string.report_dialog_zip_button))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    val report = reportManager.generateDailyReport(target)
                    reportManager.shareFile(report)
                    reportTargetRecords = null
                }) {
                    Text(stringResource(R.string.report_dialog_text_button))
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
                onShowSnackbar(context.getString(R.string.meter_paired_success, device.name ?: device.address), null, null)
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
    val hasAudio = record.filePath.isNotBlank()

    NoiseCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .combinedClickable(
                onClick = { if (hasAudio) onPlay() },
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
                        StatusPill(text = stringResource(R.string.badge_quiet_hour), type = StatusPillType.WARNING)
                    }
                    if (!hasAudio) {
                        Spacer(modifier = Modifier.width(6.dp))
                        StatusPill(text = stringResource(R.string.badge_no_audio), type = StatusPillType.IDLE)
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                // Prominente Anzeige des kalibrierten vs. unkalibrierten Werts mit klarem Herkunfts-Badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 2.dp)
                ) {
                    if (record.calibratedDbA != null) {
                        val unit = if (record.meterWeighting != null) "dB(${record.meterWeighting})" else "dB(A)"
                        StatusPill(text = stringResource(R.string.badge_pce323), type = StatusPillType.CALIBRATED)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${String.format(Locale.getDefault(), "%.1f", record.calibratedDbA)} $unit",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (record.dbValue > 0) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.source_mic_format, record.dbValue),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        StatusPill(text = stringResource(R.string.badge_microphone), type = StatusPillType.NEUTRAL)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${String.format(Locale.getDefault(), "%.1f", record.dbValue)} dB (${stringResource(R.string.badge_microphone)})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                if (record.detectedLabel != null) {
                    Text(
                        text = stringResource(R.string.label_ai_prefix, record.detectedLabel ?: ""),
                        color = MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (record.label != null) {
                    Text(
                        text = stringResource(R.string.label_user_prefix, record.label ?: ""),
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
                    contentDescription = stringResource(R.string.filter_favorites),
                    tint = if (record.favorite) Color(0xFFFFB300) else MaterialTheme.colorScheme.outline
                )
            }

            if (hasAudio) {
                IconButton(
                    onClick = onAiRecognize,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_ai_batch), tint = MaterialTheme.colorScheme.secondary)
                }
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error)
            }

            if (hasAudio) {
                IconButton(
                    onClick = onPlay,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.audio_play), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf(
                stringResource(R.string.category_drilling),
                stringResource(R.string.category_hammering),
                stringResource(R.string.category_traffic)
            ).forEach { label ->
                AssistChip(
                    onClick = { onLabel(label) },
                    label = { Text(label) },
                    modifier = Modifier.height(28.dp)
                )
            }
            AssistChip(
                onClick = onLearn,
                label = { Text(stringResource(R.string.action_learn_pattern)) },
                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp)) },
                modifier = Modifier.height(28.dp)
            )
        }
    }
}
