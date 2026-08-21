package com.example.lrmprotokoll.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.data.SessionEntity
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.Locale

/**
 * Die Protokollansicht (Plan Abschnitt 9): eine Liste aller Überwachungsperioden
 * ([SessionEntity], Plan 8.1) - für Kennwerte und Ausfallbänder einer einzelnen Session siehe
 * [ProtokollDetailScreen]. Bewusst nur eine Liste, kein eigenes Kennwerte-Vorschaufeld je Zeile:
 * das würde für jede Session sämtliche Messwerte laden, nur um sie sofort wieder zu verwerfen -
 * die Berechnung passiert erst beim Öffnen einer einzelnen Session.
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
                title = { Text(stringResource(R.string.nav_protocol)) },
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
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            if (sessions.isEmpty()) {
                Text(
                    text = stringResource(R.string.empty_protocol_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn {
                    items(sessions, key = { it.id }) { session ->
                        SessionZeile(session, onClick = { onOpenSession(session.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionZeile(session: SessionEntity, onClick: () -> Unit) {
    val formatierer = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(formatierer.format(session.startedAt), style = MaterialTheme.typography.bodyLarge)
            Text(
                buildString {
                    append(session.deviceName)
                    append(" · ")
                    if (session.endedAt != null) {
                        val dauer = Duration.ofMillis(session.endedAt - session.startedAt)
                        append(formatiereDauer(dauer))
                    } else {
                        append("läuft noch")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Gemeinsame Dauer-Formatierung für Protokoll-Liste und -Detail. */
internal fun formatiereDauer(dauer: Duration): String {
    val stunden = dauer.toHours()
    val minuten = dauer.toMinutesPart()
    return if (stunden > 0) "${stunden} h ${minuten} min" else "${minuten} min"
}
