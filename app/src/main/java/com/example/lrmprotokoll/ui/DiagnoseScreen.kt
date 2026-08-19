package com.example.lrmprotokoll.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.data.DiagnosticLogEntity
import com.example.lrmprotokoll.data.DriveDailyFileEntity
import com.example.lrmprotokoll.data.DriveSyncState
import com.example.lrmprotokoll.messreihe.zaehleReconnects
import com.example.lrmprotokoll.meter.label
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Der Diagnose-Screen (Plan Abschnitt 9) - "kein Luxus": bei einer Dauerüberwachung, die
 * alarmiert, muss nachvollziehbar sein, warum ein Alarm ausgelöst wurde oder ausblieb.
 *
 * Zustandsautomat und Decode-Fehlerrate sind live (StateFlow-basiert), Reconnect-Zähler,
 * Diagnose-Log und Sync-Historie werden einmalig beim Öffnen geladen (siehe
 * [ProtokollDetailScreen]-KDoc für dieselbe Abwägung - die zugrunde liegenden DAOs liefern kein
 * `Flow`). Der Reconnect-Zähler bezieht sich auf die letzte bzw. laufende Session, nicht auf die
 * gesamte App-Historie - das ist der diagnostisch relevante Zeitraum.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnoseScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as LaermprotokollApp).container }

    val verbindungszustand by container.connectionSupervisor.state.collectAsState()
    val frameQuality by container.meterTransport.frameQuality.collectAsState()

    var reconnectZaehler by remember { mutableStateOf(0) }
    var diagnoseLog by remember { mutableStateOf<List<DiagnosticLogEntity>>(emptyList()) }
    var syncHistorie by remember { mutableStateOf<List<DriveDailyFileEntity>>(emptyList()) }

    LaunchedEffect(Unit) {
        val db = container.database
        val session = db.sessionDao().letzte()
        if (session != null) {
            reconnectZaehler = zaehleReconnects(db.connectionEventDao().fuerSession(session.id))
        }
        diagnoseLog = db.diagnosticLogDao().alle()
        syncHistorie = db.driveDailyFileDao().alle()
    }

    val fehlerrateProzent = if (frameQuality.totalFrames > 0) {
        frameQuality.errorFrames * 100.0 / frameQuality.totalFrames
    } else {
        0.0
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnose") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            item {
                Text("Zustand", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                Text(verbindungszustand.label(), style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Reconnects (aktuelle/letzte Session): $reconnectZaehler",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Decode-Fehlerrate: ${String.format(Locale.getDefault(), "%.1f", fehlerrateProzent)} % " +
                        "(${frameQuality.errorFrames}/${frameQuality.totalFrames} seit letztem Verbindungsaufbau)",
                    style = MaterialTheme.typography.bodyMedium,
                )

                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                Text("Diagnose-Log (${diagnoseLog.size})", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                if (diagnoseLog.isEmpty()) {
                    Text(
                        "Kein Eintrag - entweder ist alles in Ordnung, oder das Diagnose-Log ist " +
                            "in den Einstellungen ausgeschaltet (Default).",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(diagnoseLog) { eintrag -> DiagnoseLogZeile(eintrag) }

            item {
                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                Text("Sync-Historie (${syncHistorie.size})", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                if (syncHistorie.isEmpty()) {
                    Text(
                        "Noch kein Drive-Sync-Lauf.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(syncHistorie) { tag -> SyncHistorieZeile(tag) }
        }
    }
}

@Composable
private fun DiagnoseLogZeile(eintrag: DiagnosticLogEntity) {
    val formatierer = remember { SimpleDateFormat("dd.MM. HH:mm:ss", Locale.getDefault()) }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(formatierer.format(eintrag.timestamp), style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.height(0.dp))
        Text(" — ${eintrag.message}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SyncHistorieZeile(tag: DriveDailyFileEntity) {
    val farbe = when (tag.state) {
        DriveSyncState.SYNCED -> MaterialTheme.colorScheme.primary
        DriveSyncState.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text("${tag.date} · ${tag.state} · ${tag.lastRowCount} Zeilen", color = farbe, style = MaterialTheme.typography.bodySmall)
        }
    }
}
