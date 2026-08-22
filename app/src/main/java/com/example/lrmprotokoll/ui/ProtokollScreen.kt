package com.example.lrmprotokoll.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.data.SessionEntity
import com.example.lrmprotokoll.ui.components.NoiseCard
import com.example.lrmprotokoll.ui.components.StatusPill
import com.example.lrmprotokoll.ui.components.StatusPillType
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.Locale

/**
 * Die Protokollansicht (Plan Abschnitt 9 & UX-Briefing): Übersicht aller Überwachungsperioden
 * im strukturierten DM-Karten-Design.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProtokollScreen(
    onBack: () -> Unit,
    onOpenDrawer: (() -> Unit)? = null,
    onOpenSession: (Long) -> Unit
) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as LaermprotokollApp).container }
    val sessions by container.database.sessionDao().alle().collectAsState(initial = emptyList())
    val verbindungszustand by container.connectionSupervisor.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.nav_protocol),
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
                    BluetoothStatusBadge(
                        state = verbindungszustand,
                        deviceName = container.settingsManager.meterDeviceName,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            if (sessions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
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
                            text = stringResource(R.string.empty_protocol_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(sessions, key = { it.id }) { session ->
                        SessionCardItem(session, onClick = { onOpenSession(session.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionCardItem(session: SessionEntity, onClick: () -> Unit) {
    val formatierer = remember { SimpleDateFormat("dd.MM.yyyy · HH:mm", Locale.getDefault()) }
    val isLive = session.endedAt == null

    NoiseCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatierer.format(session.startedAt),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = session.deviceName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("·", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    Text(
                        text = if (session.endedAt != null) {
                            val dauer = Duration.ofMillis(session.endedAt - session.startedAt)
                            formatiereDauer(dauer)
                        } else {
                            "Läuft aktuell"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isLive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isLive) {
                    StatusPill(text = "LIVE", type = StatusPillType.CONNECTED)
                } else {
                    StatusPill(text = "Archiv", type = StatusPillType.NEUTRAL)
                }
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

/** Gemeinsame Dauer-Formatierung für Protokoll-Liste und -Detail. */
internal fun formatiereDauer(dauer: Duration): String {
    val stunden = dauer.toHours()
    val minuten = dauer.toMinutesPart()
    return if (stunden > 0) "${stunden} h ${minuten} min" else "${minuten} min"
}
