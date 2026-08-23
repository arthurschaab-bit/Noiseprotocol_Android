package com.example.lrmprotokoll.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.data.MinuteAggregateEntity
import com.example.lrmprotokoll.data.SessionEntity
import com.example.lrmprotokoll.messreihe.AkustischeKennwerte
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.*

const val PROTOKOLL_SEARCH_BAR_TAG = "protokoll_search_bar"

/**
 * Moderner Protokoll-History Screen nach Screen 4 des neuen Designs.
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

    val filteredSessions = remember(sessions, searchQuery, filterOnlyWithEvents) {
        sessions.filter { s ->
            val matchQuery = searchQuery.isBlank() ||
                    s.deviceName.contains(searchQuery, ignoreCase = true) ||
                    SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(s.startedAt).contains(searchQuery, ignoreCase = true)
            matchQuery
        }
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

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.protocol_recent_measurements),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(10.dp))

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
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    items(filteredSessions, key = { it.id }) { session ->
                        ModernSessionCard(
                            session = session,
                            db = db,
                            onClick = { onOpenSession(session.id) }
                        )
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
}

@Composable
private fun ModernSessionCard(
    session: SessionEntity,
    db: com.example.lrmprotokoll.data.AppDatabase,
    onClick: () -> Unit
) {
    val isLive = session.endedAt == null
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    val dateStr = remember(session.startedAt) { dateFormat.format(session.startedAt) }
    val nowText = stringResource(R.string.protocol_time_now)
    val timeRangeStr = remember(session.startedAt, session.endedAt, nowText) {
        val startStr = timeFormat.format(session.startedAt)
        val endStr = if (session.endedAt != null) timeFormat.format(session.endedAt) else nowText
        "$startStr – $endStr"
    }

    var aggregate by remember { mutableStateOf<List<MinuteAggregateEntity>>(emptyList()) }
    var kennwerte by remember { mutableStateOf<AkustischeKennwerte.Kennwerte?>(null) }
    var eventCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(session.id) {
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
        val records = db.noiseDao().zwischenZeitpunkt(session.startedAt, session.endedAt ?: System.currentTimeMillis())
        eventCount = records.size
    }

    val durationStr = remember(session.startedAt, session.endedAt) {
        val end = session.endedAt ?: System.currentTimeMillis()
        val d = Duration.ofMillis((end - session.startedAt).coerceAtLeast(0))
        val h = d.toHours()
        val m = d.toMinutesPart()
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
    val minuten = dauer.toMinutesPart()
    return if (stunden > 0) "${stunden} h ${minuten} min" else "${minuten} min"
}
