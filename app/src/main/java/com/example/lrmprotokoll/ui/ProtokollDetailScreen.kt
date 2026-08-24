package com.example.lrmprotokoll.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.lrmprotokoll.BuildConfig
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.data.MeasurementEntity
import com.example.lrmprotokoll.data.MinuteAggregateEntity
import com.example.lrmprotokoll.data.NoiseRecord
import com.example.lrmprotokoll.data.SessionEntity
import com.example.lrmprotokoll.messreihe.AkustischeKennwerte
import com.example.lrmprotokoll.messreihe.Ausfallband
import com.example.lrmprotokoll.messreihe.downsampleAggregateFuerChart
import com.example.lrmprotokoll.messreihe.downsampleMesswerteFuerChart
import com.example.lrmprotokoll.messreihe.leiteAusfallbaenderAb
import com.example.lrmprotokoll.report.MessreiheExport
import com.example.lrmprotokoll.ui.components.NoiseCard
import com.example.lrmprotokoll.ui.components.NoiseHeaderCard
import com.example.lrmprotokoll.ui.components.StatusPill
import com.example.lrmprotokoll.ui.components.StatusPillType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.Date
import java.util.Locale

/**
 * Revisionssichere Protokoll-Detailansicht mit 1-Sekunden-Zusammenfassung, interaktivem
 * Pegelverlauf, Vorfallsliste und technischem Audit-Bericht (UX-Briefing Punkte 5, 14, 16, 18, 19).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProtokollDetailScreen(
    sessionId: Long,
    onBack: () -> Unit,
    onShowSnackbar: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as LaermprotokollApp).container }
    val scope = rememberCoroutineScope()
    val export = remember { MessreiheExport(context) }
    val supervisor = container.connectionSupervisor
    val connectionState by supervisor.state.collectAsState()

    var session by remember { mutableStateOf<SessionEntity?>(null) }
    var messwerte by remember { mutableStateOf<List<MeasurementEntity>>(emptyList()) }
    var aggregate by remember { mutableStateOf<List<MinuteAggregateEntity>>(emptyList()) }
    var sessionRecords by remember { mutableStateOf<List<NoiseRecord>>(emptyList()) }
    var kennwerte by remember { mutableStateOf<AkustischeKennwerte.Kennwerte?>(null) }
    var ausfallbaender by remember { mutableStateOf<List<Ausfallband>>(emptyList()) }
    var geladen by remember { mutableStateOf(false) }
    var jetzt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showAuditDetails by remember { mutableStateOf(false) }

    // Live-Beobachtung der Session und zugehörigen Daten
    LaunchedEffect(sessionId) {
        val db = container.database

        launch {
            db.sessionDao().byIdFlow(sessionId).collectLatest { geladeneSession ->
                session = geladeneSession
                if (geladeneSession != null) {
                    val ende = geladeneSession.endedAt
                    if (ende == null) {
                        db.noiseDao().abZeitpunktFlow(geladeneSession.startedAt).collectLatest { recs ->
                            sessionRecords = recs
                        }
                    } else {
                        db.noiseDao().zwischenZeitpunktFlow(geladeneSession.startedAt, ende).collectLatest { recs ->
                            sessionRecords = recs
                        }
                    }
                }
            }
        }

        launch {
            db.measurementDao().fuerSessionFlow(sessionId).collectLatest { geladeneMesswerte ->
                messwerte = geladeneMesswerte
                if (geladeneMesswerte.isNotEmpty()) {
                    kennwerte = AkustischeKennwerte.berechne(geladeneMesswerte)
                } else {
                    val geladeneAggregate = db.minuteAggregateDao().fuerSession(sessionId)
                    aggregate = geladeneAggregate
                    kennwerte = AkustischeKennwerte.ausAggregaten(geladeneAggregate)
                }
                geladen = true
            }
        }

        launch {
            db.connectionEventDao().fuerSessionFlow(sessionId).collectLatest { events ->
                ausfallbaender = leiteAusfallbaenderAb(events, session?.endedAt)
            }
        }

        val initialSession = db.sessionDao().byId(sessionId)
        if (initialSession != null) {
            session = initialSession
            val events = db.connectionEventDao().fuerSession(sessionId)
            ausfallbaender = leiteAusfallbaenderAb(events, initialSession.endedAt)
        }
        geladen = true
    }

    LaunchedEffect(session?.endedAt) {
        while (session != null && session?.endedAt == null) {
            jetzt = System.currentTimeMillis()
            delay(1000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.protocol_tab_sessions),
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    BluetoothStatusBadge(
                        state = connectionState,
                        deviceName = session?.deviceName,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                }
            )
        }
    ) { padding ->
        if (!geladen) {
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        val s = session
        if (s == null) {
            Box(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.protocol_session_not_found), style = MaterialTheme.typography.bodyMedium)
            }
            return@Scaffold
        }

        val formatierer = remember { SimpleDateFormat("dd.MM.yyyy · HH:mm:ss", Locale.getDefault()) }
        val isLive = s.endedAt == null
        val sessionEndeFuerChart = s.endedAt ?: jetzt

        val chartSpalten = remember(messwerte, aggregate, s.startedAt, sessionEndeFuerChart) {
            if (messwerte.isNotEmpty()) {
                downsampleMesswerteFuerChart(messwerte, s.startedAt, sessionEndeFuerChart)
            } else {
                downsampleAggregateFuerChart(aggregate, s.startedAt, sessionEndeFuerChart)
            }
        }

        val dauerText = if (s.endedAt != null) {
            formatiereDauer(Duration.ofMillis(s.endedAt - s.startedAt))
        } else {
            formatiereDauer(Duration.ofMillis((jetzt - s.startedAt).coerceAtLeast(0)))
        }

        val threshold = container.settingsManager.dbThreshold.toDouble()

        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. KOMPAKTE 1-SEKUNDEN ZUSAMMENFASSUNG (Dauer, Leq, Max, Events)
            item {
                NoiseHeaderCard(
                    title = stringResource(R.string.protocol_detail_metrics),
                    subtitle = "Start: ${formatierer.format(Date(s.startedAt))} · ${s.deviceName ?: stringResource(R.string.protocol_not_specified)}",
                    statusBadge = {
                        if (isLive) {
                            StatusPill(text = stringResource(R.string.protocol_live_active), type = StatusPillType.CONNECTED)
                        } else {
                            StatusPill(text = stringResource(R.string.protocol_completed), type = StatusPillType.NEUTRAL)
                        }
                    }
                ) {
                    // 4-Kachel Metriken
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        SummaryMetricItem(stringResource(R.string.stat_duration), dauerText, modifier = Modifier.weight(1f))
                        SummaryMetricItem(
                            "📈 LAeq",
                            kennwerte?.leqDb?.let { "%.1f dB".format(Locale.US, it) } ?: "-- dB",
                            modifier = Modifier.weight(1f)
                        )
                        SummaryMetricItem(
                            "⚡ LMax",
                            kennwerte?.maxDb?.let { "%.1f dB".format(Locale.US, it) } ?: "-- dB",
                            modifier = Modifier.weight(1f)
                        )
                        SummaryMetricItem(
                            stringResource(R.string.stat_incidents),
                            "${sessionRecords.size}",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // 2. INTERAKTIVER PEGELVERLAUF
            item {
                NoiseHeaderCard(
                    title = stringResource(R.string.cockpit_history_title),
                    subtitle = stringResource(R.string.protocol_detail_zoom_hint),
                    statusBadge = {
                        if (isLive) StatusPill(text = stringResource(R.string.protocol_live_curve), type = StatusPillType.CONNECTED)
                    }
                ) {
                    PegelverlaufChart(
                        spalten = chartSpalten,
                        ausfallbaender = ausfallbaender,
                        sessionStart = s.startedAt,
                        sessionEnde = sessionEndeFuerChart,
                        events = sessionRecords,
                        thresholdDb = threshold,
                        laeqDb = kennwerte?.leqDb,
                        isLive = isLive,
                        height = 180.dp
                    )
                }
            }

            // 3. EXPORT & BERICHTSAKTIONEN
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val k = kennwerte ?: return@Button
                            scope.launch {
                                val datei = withContext(Dispatchers.IO) { export.exportierePdf(s, k, ausfallbaender) }
                                export.teilen(datei)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.action_export_pdf))
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val datei = withContext(Dispatchers.IO) { export.exportiereCsv(s, messwerte, aggregate) }
                                export.teilen(datei)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(AppIcons.BarChart, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.action_export_csv))
                    }
                }
            }

            // 4. ERFASSTE EREIGNISSE & VORFÄLLE
            if (sessionRecords.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.protocol_documented_events, sessionRecords.size),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                items(sessionRecords, key = { it.id }) { record ->
                    NoiseCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = record.label ?: record.detectedLabel ?: "Lärmereignis",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    StatusPill(
                                        text = "%.1f dB".format(Locale.US, record.calibratedDbA ?: record.dbValue),
                                        type = StatusPillType.WARNING
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(record.timestamp)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (!record.notes.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = stringResource(R.string.protocol_notes_label, record.notes),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 5. REVISIONSSICHERE AUDIT-DETAILS (Aufklappbar für Techniker/Behörden)
            item {
                NoiseCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showAuditDetails = !showAuditDetails }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.protocol_audit_header),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Icon(
                            imageVector = if (showAuditDetails) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null
                        )
                    }

                    AnimatedVisibility(visible = showAuditDetails) {
                        Column(modifier = Modifier.padding(top = 12.dp)) {
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(8.dp))
                            AuditDetailRow("Geräte-ID / MAC", s.deviceAddress ?: "Internes Mikrofon")
                            val paramBewertung = s.weighting?.let { "dB($it)" } ?: "dB(A)"
                            val paramZeit = s.timeWeighting?.let { "$it (125 ms)" } ?: "FAST (125 ms)"
                            AuditDetailRow("Messparameter", "$paramBewertung · $paramZeit")
                            AuditDetailRow("Messpunkte erfasst", "${kennwerte?.sampleCount ?: messwerte.size}")
                            AuditDetailRow("Verbindungsausfälle", "${ausfallbaender.size} Vorfälle")
                            kennwerte?.l10Db?.let { AuditDetailRow("L10 (Spitzenpegel)", "%.1f dB".format(Locale.US, it)) }
                            kennwerte?.l50Db?.let { AuditDetailRow("L50 (Median)", "%.1f dB".format(Locale.US, it)) }
                            kennwerte?.l90Db?.let { AuditDetailRow("L90 (Grundgeräusch)", "%.1f dB".format(Locale.US, it)) }
                            AuditDetailRow("App-Version", "Noise Protocol v${BuildConfig.VERSION_NAME}")
                        }
                    }
                }
            }

            // 6. AUSFALLBÄNDER LISTE
            if (ausfallbaender.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.protocol_outages_header, ausfallbaender.size),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                items(ausfallbaender) { band ->
                    val ongoingText = stringResource(R.string.protocol_outage_ongoing)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "${formatierer.format(band.von)} – ${if (band.bis != null) formatierer.format(band.bis) else ongoingText}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold
                                )
                                if (band.bis != null) {
                                    Text(
                                        text = stringResource(R.string.protocol_outage_duration, formatiereDauer(Duration.ofMillis(band.bis - band.von))),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                            StatusPill(text = stringResource(R.string.protocol_no_data), type = StatusPillType.ERROR)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryMetricItem(title: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun AuditDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}
