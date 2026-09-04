package com.example.lrmprotokoll.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.drive.DriveUploadUebersicht
import com.example.lrmprotokoll.drive.UploadEintrag
import com.example.lrmprotokoll.drive.UploadZustand
import com.example.lrmprotokoll.messreihe.formatiereBytes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val DRIVE_UPLOAD_LISTE_TAG = "drive_upload_liste"

/**
 * Zeigt, was nach Google Drive hochgeladen wurde, was gerade laeuft und was noch aussteht
 * (Owner-Wunsch).
 *
 * Bis hierhin gab es nur eine einzige Zeile in den Einstellungen ("zuletzt synchronisiert ...").
 * Ob ein bestimmtes Video oder Foto tatsaechlich in der Cloud liegt, liess sich daraus nicht
 * ablesen - bei einem Beweismittel ist das die entscheidende Frage.
 *
 * Die Zusammenstellung selbst steht in [DriveUploadUebersicht] und ist dort ohne Netz getestet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveUploadScreen(
    onBack: () -> Unit,
    onOpenDrawer: () -> Unit,
) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as LaermprotokollApp).container }
    val db = container.database
    val settings = container.settingsManager

    val tagesdateien by db.driveDailyFileDao().alle().collectAsState(initial = emptyList())
    val fotos by db.dokumentationsFotoDao().neuesteFlow().collectAsState(initial = emptyList())
    val videos by db.beweisVideoDao().neuesteFlow().collectAsState(initial = emptyList())

    val eintraege = remember(tagesdateien, fotos, videos) {
        DriveUploadUebersicht.baue(tagesdateien, fotos, videos)
    }
    val zusammenfassung = remember(eintraege) { DriveUploadUebersicht.zusammenfassung(eintraege) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Drive-Uploads") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Menü")
                    }
                },
            )
        },
    ) { innen ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innen)
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = if (settings.driveSyncEnabled) {
                    "${zusammenfassung[UploadZustand.HOCHGELADEN] ?: 0} hochgeladen · " +
                        "${zusammenfassung[UploadZustand.LAEUFT] ?: 0} laufen · " +
                        "${zusammenfassung[UploadZustand.OFFEN] ?: 0} offen · " +
                        "${zusammenfassung[UploadZustand.FEHLGESCHLAGEN] ?: 0} fehlgeschlagen"
                } else {
                    "Drive-Synchronisation ist ausgeschaltet – es wird nichts hochgeladen."
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 10.dp),
            )
            settings.driveSyncLastMessage?.takeIf { it.isNotBlank() }?.let { meldung ->
                Text(
                    text = "Letzter Lauf: $meldung",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            HorizontalDivider()

            if (eintraege.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Noch nichts aufgezeichnet, was hochgeladen werden könnte.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(DRIVE_UPLOAD_LISTE_TAG),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(eintraege, key = { "${it.kategorie}-${it.bezeichnung}-${it.zeitpunkt}" }) { eintrag ->
                        UploadZeile(eintrag)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun UploadZeile(eintrag: UploadEintrag) {
    val zeitformat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = eintrag.bezeichnung,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "${eintrag.kategorie.ordnername} · ${zeitformat.format(Date(eintrag.zeitpunkt))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (eintrag.zustand == UploadZustand.LAEUFT) {
                val anteil = eintrag.prozent
                if (anteil != null) {
                    LinearProgressIndicator(
                        progress = { anteil / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    )
                    Text(
                        text = "$anteil % · ${formatiereBytes(eintrag.gesendeteBytes)} von ${formatiereBytes(eintrag.gesamtBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // Kein erfundener Balken, wo nichts gemessen wird.
                    Text(
                        text = "Übertragung läuft",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            text = when (eintrag.zustand) {
                UploadZustand.HOCHGELADEN -> "✓ hochgeladen"
                UploadZustand.LAEUFT -> "↑ läuft"
                UploadZustand.OFFEN -> "· offen"
                UploadZustand.FEHLGESCHLAGEN -> "✕ fehlgeschlagen"
            },
            style = MaterialTheme.typography.bodySmall,
            color = when (eintrag.zustand) {
                UploadZustand.FEHLGESCHLAGEN -> MaterialTheme.colorScheme.error
                UploadZustand.HOCHGELADEN -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
