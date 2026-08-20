package com.example.lrmprotokoll.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.data.MeasurementEntity
import com.example.lrmprotokoll.data.MinuteAggregateEntity
import com.example.lrmprotokoll.data.SessionEntity
import com.example.lrmprotokoll.messreihe.AkustischeKennwerte
import com.example.lrmprotokoll.messreihe.Ausfallband
import com.example.lrmprotokoll.messreihe.downsampleAggregateFuerChart
import com.example.lrmprotokoll.messreihe.downsampleMesswerteFuerChart
import com.example.lrmprotokoll.messreihe.leiteAusfallbaenderAb
import com.example.lrmprotokoll.report.MessreiheExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.Locale

/**
 * Kennwerte und Ausfallbänder EINER Session (Plan Abschnitt 9).
 *
 * Unterstützt sowohl abgeschlossene als auch aktuell laufende Sessions:
 * Bei laufenden Sessions aktualisiert sich der Pegelverlauf live und verlängert sich
 * dynamisch über die Zeitachse.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProtokollDetailScreen(sessionId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as LaermprotokollApp).container }
    val scope = rememberCoroutineScope()
    val export = remember { MessreiheExport(context) }
    val supervisor = container.connectionSupervisor
    val connectionState by supervisor.state.collectAsState()

    var session by remember { mutableStateOf<SessionEntity?>(null) }
    var messwerte by remember { mutableStateOf<List<MeasurementEntity>>(emptyList()) }
    var aggregate by remember { mutableStateOf<List<MinuteAggregateEntity>>(emptyList()) }
    var kennwerte by remember { mutableStateOf<AkustischeKennwerte.Kennwerte?>(null) }
    var ausfallbaender by remember { mutableStateOf<List<Ausfallband>>(emptyList()) }
    var geladen by remember { mutableStateOf(false) }
    var jetzt by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Live-Beobachtung der Session und deren Messdaten
    LaunchedEffect(sessionId) {
        val db = container.database

        // Session-Stammdaten beobachten
        launch {
            db.sessionDao().byIdFlow(sessionId).collectLatest { geladeneSession ->
                session = geladeneSession
            }
        }

        // Messwerte beobachten
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

        // Verbindungsereignisse beobachten
        launch {
            db.connectionEventDao().fuerSessionFlow(sessionId).collectLatest { events ->
                ausfallbaender = leiteAusfallbaenderAb(events, session?.endedAt)
            }
        }

        // Falls noch keine Messwerte da sind, initiale Aggregate laden
        val initialSession = db.sessionDao().byId(sessionId)
        if (initialSession != null) {
            session = initialSession
            val events = db.connectionEventDao().fuerSession(sessionId)
            ausfallbaender = leiteAusfallbaenderAb(events, initialSession.endedAt)
        }
        geladen = true
    }

    // Ticker für laufende Sessions, damit die Zeitachse im Live-Betrieb stetig wächst
    LaunchedEffect(session?.endedAt) {
        while (session != null && session?.endedAt == null) {
            jetzt = System.currentTimeMillis()
            delay(1000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Session") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
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
            Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
                Text("Lädt…", style = MaterialTheme.typography.bodyMedium)
            }
            return@Scaffold
        }
        val s = session
        if (s == null) {
            Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
                Text("Session nicht gefunden.", style = MaterialTheme.typography.bodyMedium)
            }
            return@Scaffold
        }

        val formatierer = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
        val isLive = s.endedAt == null
        val sessionEndeFuerChart = s.endedAt ?: jetzt

        val chartSpalten = remember(messwerte, aggregate, s.startedAt, sessionEndeFuerChart) {
            if (messwerte.isNotEmpty()) {
                downsampleMesswerteFuerChart(messwerte, s.startedAt, sessionEndeFuerChart)
            } else {
                downsampleAggregateFuerChart(aggregate, s.startedAt, sessionEndeFuerChart)
            }
        }

        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            item {
                // Header mit Gerätename und Status-Badge
                SessionStatusHeader(session = s, formatierer = formatierer, jetztMillis = jetzt)
                Spacer(modifier = Modifier.height(14.dp))

                // Kompakter/einklappbarer Kennwerte-Block
                Text("Kennwerte", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                KompakterKennwerteBlock(kennwerte)
                Spacer(modifier = Modifier.height(14.dp))

                // Pegelverlauf Chart
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Pegelverlauf", style = MaterialTheme.typography.titleSmall)
                    if (isLive) {
                        Text(
                            "Live",
                            color = Color(0xFF2E7D32),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                PegelverlaufChart(
                    spalten = chartSpalten,
                    ausfallbaender = ausfallbaender,
                    sessionStart = s.startedAt,
                    sessionEnde = sessionEndeFuerChart,
                    isLive = isLive,
                )
                Spacer(modifier = Modifier.height(14.dp))

                Row {
                    Button(onClick = {
                        scope.launch {
                            val datei = withContext(Dispatchers.IO) { export.exportiereCsv(s, messwerte, aggregate) }
                            export.teilen(datei)
                        }
                    }) { Text("CSV exportieren") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = {
                        val k = kennwerte ?: return@Button
                        scope.launch {
                            val datei = withContext(Dispatchers.IO) { export.exportierePdf(s, k, ausfallbaender) }
                            export.teilen(datei)
                        }
                    }) { Text("PDF exportieren") }
                }

                Spacer(modifier = Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    "Ausfälle (${ausfallbaender.size})",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (ausfallbaender.isEmpty()) {
                    Text(
                        "Keine Verbindungsausfälle während dieser Session.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(ausfallbaender) { band -> AusfallZeile(band, formatierer) }
        }
    }
}

@Composable
private fun SessionStatusHeader(session: SessionEntity, formatierer: SimpleDateFormat, jetztMillis: Long) {
    val isLive = session.endedAt == null

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isLive) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "${session.deviceName} (${session.deviceAddress})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isLive) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outlineVariant
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        if (isLive) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(
                            text = if (isLive) "LIVE" else "BEENDET",
                            color = if (isLive) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                buildString {
                    append("Start: ${formatierer.format(session.startedAt)}")
                    if (session.endedAt != null) {
                        append(" · Dauer: ")
                        val dauer = Duration.ofMillis(session.endedAt - session.startedAt)
                        append(formatiereDauer(dauer))
                    } else {
                        append(" · Läuft seit ")
                        val dauer = Duration.ofMillis((jetztMillis - session.startedAt).coerceAtLeast(0))
                        append(formatiereDauer(dauer))
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (isLive) Color(0xFF1B5E20) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun KompakterKennwerteBlock(kennwerte: AkustischeKennwerte.Kennwerte?) {
    if (kennwerte == null || kennwerte.sampleCount == 0) {
        Text("Keine Messwerte in dieser Session.", style = MaterialTheme.typography.bodyMedium)
        return
    }

    var detailsGeoeffnet by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Kompakte Hauptübersicht
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.weight(1f)) {
                    Text(
                        "LAeq: ${String.format(Locale.getDefault(), "%.1f", kennwerte.leqDb)} dB",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        "Max: ${String.format(Locale.getDefault(), "%.1f", kennwerte.maxDb)} dB",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        "Min: ${String.format(Locale.getDefault(), "%.1f", kennwerte.minDb)} dB",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                IconButton(
                    onClick = { detailsGeoeffnet = !detailsGeoeffnet },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        if (detailsGeoeffnet) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (detailsGeoeffnet) "Details ausblenden" else "Details einblenden"
                    )
                }
            }

            AnimatedVisibility(visible = detailsGeoeffnet) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    if (kennwerte.l10Db != null) KennwertZeile("L10 (Spitzenbelastung)", kennwerte.l10Db)
                    if (kennwerte.l50Db != null) KennwertZeile("L50 (Medianpegel)", kennwerte.l50Db)
                    if (kennwerte.l90Db != null) KennwertZeile("L90 (Hintergrundpegel)", kennwerte.l90Db)
                    Text(
                        "${kennwerte.sampleCount} Messwerte",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun KennwertZeile(label: String, wertDb: Double?) {
    if (wertDb == null) return
    Text("$label: ${String.format(Locale.getDefault(), "%.1f", wertDb)} dB", style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun AusfallZeile(band: Ausfallband, formatierer: SimpleDateFormat) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row {
                Text(formatierer.format(band.von), style = MaterialTheme.typography.bodyMedium)
                Text(" – ", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (band.bis != null) formatierer.format(band.bis) else "andauernd",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (band.bis != null) {
                Text(
                    formatiereDauer(Duration.ofMillis(band.bis - band.von)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

