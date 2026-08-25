package com.example.lrmprotokoll.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.audio.AudioRecordingService
import com.example.lrmprotokoll.data.MinuteAggregateEntity
import com.example.lrmprotokoll.data.NoiseRecord
import com.example.lrmprotokoll.data.SessionEntity
import com.example.lrmprotokoll.messreihe.AkustischeKennwerte
import com.example.lrmprotokoll.report.ReportManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.*

const val PROTOKOLL_SEARCH_BAR_TAG = "protokoll_search_bar"

/**
 * Moderner Protokoll-History Screen nach Screen 4 des neuen Designs mit
 * tagesweiser Gruppierung und Tagesreport-Funktion.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProtokollScreen(
    onBack: () -> Unit,
    onOpenDrawer: (() -> Unit)? = null,
    onOpenSession: (Long) -> Unit,
    onStartNewMeasurement: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as LaermprotokollApp).container }
    val sessions by container.database.sessionDao().alle().collectAsState(initial = emptyList())
    val db = container.database

    var searchQuery by remember { mutableStateOf("") }
    var filterOnlyWithEvents by remember { mutableStateOf(false) }
    var tagesreportSelectedDay by remember { mutableStateOf<Pair<String, List<SessionEntity>>?>(null) }

    val filteredSessions = remember(sessions, searchQuery, filterOnlyWithEvents) {
        sessions.filter { s ->
            val matchQuery = searchQuery.isBlank() ||
                    s.deviceName.contains(searchQuery, ignoreCase = true) ||
                    SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(s.startedAt).contains(searchQuery, ignoreCase = true)
            matchQuery
        }
    }

    val sessionsByDay = remember(filteredSessions) {
        val df = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        filteredSessions.groupBy { session -> df.format(Date(session.startedAt)) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.nav_protocol),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
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
                    IconButton(onClick = { filterOnlyWithEvents = !filterOnlyWithEvents }) {
                        Icon(
                            imageVector = AppIcons.FilterList,
                            contentDescription = "Filter",
                            tint = if (filterOnlyWithEvents) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            if (onStartNewMeasurement != null) {
                ExtendedFloatingActionButton(
                    onClick = onStartNewMeasurement,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.protocol_new_measurement), fontWeight = FontWeight.Bold) },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.protocol_search_placeholder)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_delete))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(PROTOKOLL_SEARCH_BAR_TAG)
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (filteredSessions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
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
                            text = if (searchQuery.isNotBlank()) stringResource(R.string.protocol_search_no_results) else stringResource(R.string.empty_protocol_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    sessionsByDay.forEach { (dayKey, daySessions) ->
                        val firstTimestamp = daySessions.firstOrNull()?.startedAt ?: System.currentTimeMillis()

                        // Day Header with Tagesreport Action
                        item(key = "day_header_$dayKey") {
                            val dayTitle = remember(dayKey, firstTimestamp) {
                                berechneTagesTitel(dayKey, firstTimestamp)
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 14.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = dayTitle,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "${daySessions.size} ${if (daySessions.size == 1) "Messreihe" else "Messreihen"}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                FilledTonalButton(
                                    onClick = { tagesreportSelectedDay = dayKey to daySessions },
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                ) {
                                    Icon(
                                        imageVector = AppIcons.Diagnose,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Tagesreport",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        // List of Sessions for this day
                        items(daySessions, key = { it.id }) { session ->
                            ModernSessionCard(
                                session = session,
                                db = db,
                                onClick = { onOpenSession(session.id) }
                            )
                        }
                    }

                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = AppIcons.Restore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.protocol_end_of_history),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    // Modal Tagesreport Dialog
    tagesreportSelectedDay?.let { (dayKey, daySessions) ->
        TagesreportDialog(
            dayKey = dayKey,
            sessions = daySessions,
            db = db,
            onDismiss = { tagesreportSelectedDay = null }
        )
    }
}

@Composable
private fun TagesreportDialog(
    dayKey: String,
    sessions: List<SessionEntity>,
    db: com.example.lrmprotokoll.data.AppDatabase,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var isComputing by remember { mutableStateOf(true) }
    var totalDurationMs by remember { mutableLongStateOf(0L) }
    var dailyLeq by remember { mutableStateOf<Double?>(null) }
    var dailyLmax by remember { mutableStateOf<Double?>(null) }
    var totalRecordsCount by remember { mutableIntStateOf(0) }
    var quietRecordsCount by remember { mutableIntStateOf(0) }
    var recordsList by remember { mutableStateOf<List<NoiseRecord>>(emptyList()) }

    val formattedDay = remember(sessions) {
        val firstTimestamp = sessions.firstOrNull()?.startedAt ?: System.currentTimeMillis()
        SimpleDateFormat("EEEE, dd. MMMM yyyy", Locale.GERMAN).format(Date(firstTimestamp))
    }

    LaunchedEffect(dayKey, sessions) {
        withContext(Dispatchers.IO) {
            var durSum = 0L
            val allAggregates = mutableListOf<MinuteAggregateEntity>()
            val allMeasurements = mutableListOf<com.example.lrmprotokoll.data.MeasurementEntity>()
            val allRecords = mutableListOf<NoiseRecord>()

            for (session in sessions) {
                val end = session.endedAt ?: (session.startedAt + 1000L)
                durSum += (end - session.startedAt).coerceAtLeast(0)

                val aggs = db.minuteAggregateDao().fuerSession(session.id)
                allAggregates.addAll(aggs)
                if (aggs.isEmpty()) {
                    val mess = db.measurementDao().fuerSession(session.id)
                    allMeasurements.addAll(mess)
                }

                val recs = db.noiseDao().zwischenZeitpunkt(session.startedAt, end)
                allRecords.addAll(recs)
            }

            val kw = if (allAggregates.isNotEmpty()) {
                AkustischeKennwerte.ausAggregaten(allAggregates)
            } else if (allMeasurements.isNotEmpty()) {
                AkustischeKennwerte.berechne(allMeasurements)
            } else null

            totalDurationMs = durSum
            dailyLeq = kw?.leqDb
            dailyLmax = kw?.maxDb ?: allRecords.maxOfOrNull { it.calibratedDbA ?: it.dbValue }
            totalRecordsCount = allRecords.size
            quietRecordsCount = allRecords.count { it.isQuietHour }
            recordsList = allRecords.sortedBy { it.timestamp }
            isComputing = false
        }
    }

    val reportText = remember(formattedDay, sessions.size, totalDurationMs, dailyLeq, dailyLmax, totalRecordsCount, quietRecordsCount, recordsList) {
        val sb = StringBuilder()
        sb.append("LÄRMPROTOKOLL - TAGESREPORT\n")
        sb.append("Datum: $formattedDay\n")
        sb.append("===========================================\n\n")
        sb.append("ZUSAMMENFASSUNG:\n")
        sb.append("• Anzahl Messreihen: ${sessions.size}\n")
        val d = Duration.ofMillis(totalDurationMs)
        val h = d.toHours()
        val m = (d.toMinutes() % 60).toInt()
        val durText = if (h > 0) "${h}h ${m}m" else "${m} min"
        sb.append("• Gesamte Messdauer: $durText\n")
        sb.append("• Durchschnittspegel LAeq: ${dailyLeq?.let { String.format(Locale.GERMAN, "%.1f dB(A)", it) } ?: "k.A."}\n")
        sb.append("• Maximaler Spitzenpegel Lmax: ${dailyLmax?.let { String.format(Locale.GERMAN, "%.1f dB(A)", it) } ?: "k.A."}\n")
        sb.append("• Dokumentierte Lärmereignisse: $totalRecordsCount\n")
        sb.append("• Davon in gesetzlichen Ruhezeiten: $quietRecordsCount\n\n")

        if (recordsList.isNotEmpty()) {
            sb.append("EINZEL-VORFÄLLE ($totalRecordsCount):\n")
            sb.append("-------------------------------------------\n")
            val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.GERMAN)
            recordsList.forEach { r ->
                val t = timeFmt.format(Date(r.timestamp))
                val pegel = r.calibratedDbA?.let { String.format(Locale.GERMAN, "%.1f dBA", it) } ?: "${String.format(Locale.GERMAN, "%.1f", r.dbValue)} dB"
                val label = r.detectedLabel ?: r.label ?: "Lärmereignis"
                val ruhe = if (r.isQuietHour) " [RUHEZEIT]" else ""
                sb.append("$t | $pegel | $label$ruhe\n")
            }
        }
        sb.toString()
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Tagesreport",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = formattedDay,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Schließen")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (isComputing) {
                    Box(modifier = Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    // Summary Grid Cards
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ReportMetricCard(
                                modifier = Modifier.weight(1f),
                                title = "Messdauer",
                                value = run {
                                    val d = Duration.ofMillis(totalDurationMs)
                                    val h = d.toHours()
                                    val m = (d.toMinutes() % 60).toInt()
                                    if (h > 0) "${h}h ${m}m" else "${m} min"
                                },
                                subtitle = "${sessions.size} Messreihen"
                            )
                            ReportMetricCard(
                                modifier = Modifier.weight(1f),
                                title = "Mittelwert LAeq",
                                value = dailyLeq?.let { String.format(Locale.GERMAN, "%.1f dB", it) } ?: "--.-",
                                subtitle = "Energetisches Mittel"
                            )
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ReportMetricCard(
                                modifier = Modifier.weight(1f),
                                title = "Spitze Lmax",
                                value = dailyLmax?.let { String.format(Locale.GERMAN, "%.1f dB", it) } ?: "--.-",
                                subtitle = "Höchster Messwert"
                            )
                            ReportMetricCard(
                                modifier = Modifier.weight(1f),
                                title = "Lärmereignisse",
                                value = totalRecordsCount.toString(),
                                subtitle = if (quietRecordsCount > 0) "$quietRecordsCount in Ruhezeit" else "Keine in Ruhezeit"
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(reportText))
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(AppIcons.Bookmark, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Kopieren")
                        }

                        Button(
                            onClick = {
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "Lärmprotokoll Tagesreport - $formattedDay")
                                    putExtra(Intent.EXTRA_TEXT, reportText)
                                }
                                val shareIntent = Intent.createChooser(sendIntent, "Tagesreport teilen")
                                context.startActivity(shareIntent)
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Teilen")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportMetricCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    subtitle: String
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ModernSessionCard(
    session: SessionEntity,
    db: com.example.lrmprotokoll.data.AppDatabase,
    onClick: () -> Unit
) {
    val serviceLaeuft by AudioRecordingService.laeuft.collectAsState()
    val isLive = session.endedAt == null && serviceLaeuft
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    val dateStr = remember(session.startedAt) { dateFormat.format(session.startedAt) }
    val nowText = stringResource(R.string.protocol_time_now)
    val timeRangeStr = remember(session.startedAt, session.endedAt, isLive, nowText) {
        val startStr = timeFormat.format(session.startedAt)
        val endStr = if (isLive) nowText else if (session.endedAt != null) timeFormat.format(session.endedAt) else timeFormat.format(session.startedAt)
        "$startStr – $endStr"
    }

    var aggregate by remember { mutableStateOf<List<MinuteAggregateEntity>>(emptyList()) }
    var kennwerte by remember { mutableStateOf<AkustischeKennwerte.Kennwerte?>(null) }
    var eventCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(session.id, isLive) {
        val agg = db.minuteAggregateDao().fuerSession(session.id)
        aggregate = agg
        if (agg.isNotEmpty()) {
            kennwerte = AkustischeKennwerte.ausAggregaten(agg)
        } else {
            val measurements = db.measurementDao().fuerSession(session.id)
            if (measurements.isNotEmpty()) {
                kennwerte = AkustischeKennwerte.berechne(measurements)
            }
        }
        val endTs = if (isLive) System.currentTimeMillis() else (session.endedAt ?: (session.startedAt + 1000L))
        val records = db.noiseDao().zwischenZeitpunkt(session.startedAt, endTs)
        eventCount = records.size
    }

    val durationStr = remember(session.startedAt, session.endedAt, isLive) {
        val end = if (isLive) System.currentTimeMillis() else (session.endedAt ?: session.startedAt)
        val d = Duration.ofMillis((end - session.startedAt).coerceAtLeast(0))
        val h = d.toHours()
        val m = (d.toMinutes() % 60).toInt()
        if (h > 0) "${h}h ${m}m" else "${m} min"
    }

    val laeqStr = kennwerte?.leqDb?.let { String.format(Locale.getDefault(), "%.1f dB", it) } ?: "--.-"
    val lmaxStr = kennwerte?.maxDb?.let { String.format(Locale.getDefault(), "%.1f dB", it) } ?: "--.-"

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
            .clickable { onClick() }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Top Row: Date, Time Range, Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = dateStr,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = timeRangeStr,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Status Badge
                val badgeText = if (isLive) stringResource(R.string.protocol_badge_active) else stringResource(R.string.protocol_badge_complete)
                val badgeBg = if (isLive) Color(0xFFDCFCE7) else MaterialTheme.colorScheme.surfaceVariant
                val badgeTextColor = if (isLive) Color(0xFF15803D) else MaterialTheme.colorScheme.onSurfaceVariant

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(badgeBg)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = badgeTextColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 4 Stats Columns Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricColumn(label = stringResource(R.string.protocol_metric_duration), value = durationStr)
                MetricColumn(label = "LAeq", value = laeqStr)
                MetricColumn(label = "LMax", value = lmaxStr)
                MetricColumn(label = stringResource(R.string.protocol_metric_events), value = eventCount.toString())
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(10.dp))

            // Bottom Row: Device badge and Details > link
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = AppIcons.Sensors,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = session.deviceName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { onClick() }
                ) {
                    Text(
                        text = stringResource(R.string.protocol_details_button),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Icon(
                        imageVector = AppIcons.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricColumn(
    label: String,
    value: String
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** Gemeinsame Dauer-Formatierung für Protokoll-Liste und -Detail. */
internal fun formatiereDauer(dauer: Duration): String {
    val stunden = dauer.toHours()
    val minuten = (dauer.toMinutes() % 60).toInt()
    return if (stunden > 0) "${stunden} h ${minuten} min" else "${minuten} min"
}

internal fun berechneTagesTitel(dayKey: String, firstTimestamp: Long): String {
    val cal = Calendar.getInstance()
    val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
    cal.add(Calendar.DAY_OF_YEAR, -1)
    val yesterdayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
    return when (dayKey) {
        todayKey -> "Heute, " + SimpleDateFormat("dd. MMMM yyyy", Locale.GERMAN).format(Date(firstTimestamp))
        yesterdayKey -> "Gestern, " + SimpleDateFormat("dd. MMMM yyyy", Locale.GERMAN).format(Date(firstTimestamp))
        else -> SimpleDateFormat("EEEE, dd. MMMM yyyy", Locale.GERMAN).format(Date(firstTimestamp))
    }
}
