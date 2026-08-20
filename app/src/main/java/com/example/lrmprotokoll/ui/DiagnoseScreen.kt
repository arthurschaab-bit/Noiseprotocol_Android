package com.example.lrmprotokoll.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.lrmprotokoll.BuildConfig
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.data.DiagnosticLogEntity
import com.example.lrmprotokoll.data.DriveDailyFileEntity
import com.example.lrmprotokoll.data.DriveSyncState
import com.example.lrmprotokoll.diagnose.DiagnosticCode
import com.example.lrmprotokoll.diagnose.DiagnosticSeverity
import com.example.lrmprotokoll.messreihe.zaehleReconnects
import com.example.lrmprotokoll.meter.label
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Der Diagnose-Screen (Plan Abschnitt 9) - "kein Luxus": bei einer Dauerüberwachung, die
 * alarmiert, muss nachvollziehbar sein, warum ein Alarm ausgelöst wurde oder ausblieb.
 *
 * Zustandsautomat, Decode-Fehlerrate, Diagnose-Log und Sync-Historie sind alle live
 * (StateFlow/Flow-basiert, M7c Aufgabe 5) - neue Einträge erscheinen, ohne den Screen neu zu
 * öffnen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnoseScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as LaermprotokollApp).container }
    val scope = rememberCoroutineScope()

    val verbindungszustand by container.connectionSupervisor.state.collectAsState()
    val frameQuality by container.meterTransport.frameQuality.collectAsState()
    val diagnoseLog by container.database.diagnosticLogDao().alle().collectAsState(initial = emptyList())
    val syncHistorie by container.database.driveDailyFileDao().alle().collectAsState(initial = emptyList())

    var remoteDiagnoseAktiv by remember { mutableStateOf(container.settingsManager.remoteDiagnoseAktiv) }
    var letzteDiagnoseId by remember { mutableStateOf(container.settingsManager.letzteDiagnoseId) }
    var reconnectZaehler by remember { mutableStateOf(0) }
    var exportiertGerade by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val db = container.database
        val session = db.sessionDao().letzte()
        if (session != null) {
            reconnectZaehler = zaehleReconnects(db.connectionEventDao().fuerSession(session.id))
        }
    }

    val fehlerrateProzent = if (frameQuality.totalFrames > 0) {
        frameQuality.errorFrames * 100.0 / frameQuality.totalFrames
    } else {
        0.0
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnose & Support") },
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

                Text("Remote-Diagnose & Datenschutz", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text("Fehlerberichte senden", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "Übermittelt technische Fehlercodes und Absturzberichte (Sentry). Keine Audiodaten, MAC-Adressen und Namen sind pseudonymisiert.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Switch(
                                checked = remoteDiagnoseAktiv,
                                onCheckedChange = { aktiv ->
                                    remoteDiagnoseAktiv = aktiv
                                    container.settingsManager.remoteDiagnoseAktiv = aktiv
                                }
                            )
                        }

                        if (letzteDiagnoseId != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Letzte Diagnose-ID:", style = MaterialTheme.typography.labelSmall)
                                    Text(letzteDiagnoseId ?: "", style = MaterialTheme.typography.bodyMedium)
                                }
                                OutlinedButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("Diagnose-ID", letzteDiagnoseId))
                                        Toast.makeText(context, "Diagnose-ID in Zwischenablage kopiert", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Text("Kopieren")
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        scope.launch {
                            exportiertGerade = true
                            try {
                                val zipFile = withContext(Dispatchers.IO) {
                                    container.supportBundleExporter.createBundle(diagnoseLog)
                                }
                                val shareIntent = container.supportBundleExporter.createShareIntent(zipFile)
                                context.startActivity(Intent.createChooser(shareIntent, "Support-Bundle teilen…"))
                            } catch (e: Exception) {
                                Toast.makeText(context, "Export fehlgeschlagen: ${e.message}", Toast.LENGTH_LONG).show()
                            } finally {
                                exportiertGerade = false
                            }
                        }
                    },
                    enabled = !exportiertGerade,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (exportiertGerade) "Erstelle Support-Bundle…" else "Support-Bundle exportieren (ZIP)")
                }

                if (BuildConfig.DEBUG) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            val id = container.diagnosticsReporter.report(
                                code = DiagnosticCode.ALERT_LOCAL_FAILED,
                                component = "DiagnoseScreen",
                                operation = "manualTest",
                                severity = DiagnosticSeverity.WARN,
                                message = "Manueller Testbericht durch Benutzer ausgelöst",
                                details = mapOf("source" to "debug_button")
                            )
                            letzteDiagnoseId = id.shortCode
                            container.settingsManager.letzteDiagnoseId = id.shortCode
                            Toast.makeText(context, "Test-Event gesendet ($id)", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Test-Diagnose-Event auslösen")
                    }
                }

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
