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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.data.SessionEntity
import com.example.lrmprotokoll.messreihe.AkustischeKennwerte
import com.example.lrmprotokoll.messreihe.Ausfallband
import com.example.lrmprotokoll.messreihe.leiteAusfallbaenderAb
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.Locale

/**
 * Kennwerte und Ausfallbänder EINER Session (Plan Abschnitt 9). Lädt beim Öffnen einmalig
 * (kein Live-Update - eine abgeschlossene oder gerade laufende Session ändert sich zwischen zwei
 * Betrachtungen selten genug, dass ein manuelles Neu-Öffnen ausreicht, siehe fehlende
 * `Flow`-Varianten der zugrunde liegenden DAOs).
 *
 * Kennwerte kommen aus den Rohwerten ([AkustischeKennwerte.berechne]), sofern noch welche da
 * sind - sonst (Retention-Job hat sie bereits verdichtet, Plan 13.2) aus den Minutenaggregaten
 * ([AkustischeKennwerte.ausAggregaten]). Beides zusammen abzufragen und den Rohwert-Fall
 * vorzuziehen ist bewusst: Rohwerte liefern die volle Genauigkeit inklusive L10/L50/L90, die
 * Aggregate nur eine Näherung.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProtokollDetailScreen(sessionId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as LaermprotokollApp).container }

    var session by remember { mutableStateOf<SessionEntity?>(null) }
    var kennwerte by remember { mutableStateOf<AkustischeKennwerte.Kennwerte?>(null) }
    var ausfallbaender by remember { mutableStateOf<List<Ausfallband>>(emptyList()) }
    var geladen by remember { mutableStateOf(false) }

    LaunchedEffect(sessionId) {
        val db = container.database
        val geladeneSession = db.sessionDao().byId(sessionId)
        session = geladeneSession

        val messwerte = db.measurementDao().fuerSession(sessionId)
        kennwerte = if (messwerte.isNotEmpty()) {
            AkustischeKennwerte.berechne(messwerte)
        } else {
            AkustischeKennwerte.ausAggregaten(db.minuteAggregateDao().fuerSession(sessionId))
        }

        val events = db.connectionEventDao().fuerSession(sessionId)
        ausfallbaender = leiteAusfallbaenderAb(events, geladeneSession?.endedAt)
        geladen = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Session") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            if (!geladen) {
                Text("Lädt…", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            val s = session
            if (s == null) {
                Text("Session nicht gefunden.", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }

            val formatierer = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
            Text(
                "${formatierer.format(s.startedAt)} — ${s.deviceName} (${s.deviceAddress})",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(16.dp))

            Text("Kennwerte", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(4.dp))
            KennwerteBlock(kennwerte)

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

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
            } else {
                LazyColumn {
                    items(ausfallbaender) { band -> AusfallZeile(band, formatierer) }
                }
            }
        }
    }
}

@Composable
private fun KennwerteBlock(kennwerte: AkustischeKennwerte.Kennwerte?) {
    if (kennwerte == null || kennwerte.sampleCount == 0) {
        Text("Keine Messwerte in dieser Session.", style = MaterialTheme.typography.bodyMedium)
        return
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(12.dp)) {
            KennwertZeile("LAeq", kennwerte.leqDb)
            KennwertZeile("Max", kennwerte.maxDb)
            KennwertZeile("Min", kennwerte.minDb)
            if (kennwerte.l10Db != null) KennwertZeile("L10", kennwerte.l10Db)
            if (kennwerte.l50Db != null) KennwertZeile("L50", kennwerte.l50Db)
            if (kennwerte.l90Db != null) KennwertZeile("L90", kennwerte.l90Db)
            Text(
                "${kennwerte.sampleCount} Messwerte",
                style = MaterialTheme.typography.bodySmall,
            )
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
