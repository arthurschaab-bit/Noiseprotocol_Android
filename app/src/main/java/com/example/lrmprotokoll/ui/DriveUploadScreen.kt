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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.drive.DriveSyncPlanung
import com.example.lrmprotokoll.drive.DriveUploadUebersicht
import com.example.lrmprotokoll.drive.UploadDateiTyp
import com.example.lrmprotokoll.drive.UploadEintrag
import com.example.lrmprotokoll.drive.UploadZustand
import com.example.lrmprotokoll.messreihe.formatiereBytes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

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
    /** F-31: Snackbar-Kanal des Scaffolds; `null` bedeutet, dass der Screen ohne Rueckmeldung auskommt. */
    onShowSnackbar: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as LaermprotokollApp).container }
    val db = container.database
    val settings = container.settingsManager
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val tagesdateien by db.driveDailyFileDao().alle().collectAsState(initial = emptyList())
    val fotos by db.dokumentationsFotoDao().neuesteFlow().collectAsState(initial = emptyList())
    val videos by db.beweisVideoDao().neuesteFlow().collectAsState(initial = emptyList())

    val eintraege = remember(tagesdateien, fotos, videos) {
        DriveUploadUebersicht.baue(tagesdateien, fotos, videos)
    }
    val zusammenfassung = remember(eintraege) { DriveUploadUebersicht.zusammenfassung(eintraege) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Drive-Uploads") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Zurück")
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
                    items(eintraege, key = { "${it.ziel.typ}-${it.ziel.kennung}" }) { eintrag ->
                        val retryErlaubt = settings.driveSyncEnabled && settings.driveFolderId != null &&
                            !settings.driveOrdnerBlockiert && when (eintrag.ziel.typ) {
                                UploadDateiTyp.TAGESDATEI -> true
                                UploadDateiTyp.FOTO -> settings.fotoDokuDriveUpload
                                UploadDateiTyp.VIDEO -> settings.videoDriveUpload
                            }
                        UploadZeile(
                            eintrag = eintrag,
                            onRetry = if (retryErlaubt && eintrag.retryMoeglich && eintrag.zustand != UploadZustand.HOCHGELADEN) {
                                {
                                    val eingeplant = DriveSyncPlanung.starteDateiErneut(context, eintrag.ziel)
                                    scope.launch {
                                        snackbar.showSnackbar(
                                            context.getString(
                                                if (eingeplant) R.string.drive_retry_queued else R.string.drive_retry_enqueue_failed,
                                            ),
                                        )
                                    }
                                }
                            } else null,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun UploadZeile(eintrag: UploadEintrag, onRetry: (() -> Unit)? = null) {
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
        Column(horizontalAlignment = Alignment.End) {
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
            if (onRetry != null) {
                TextButton(onClick = onRetry, modifier = Modifier.testTag("retry_upload_${eintrag.ziel.typ}_${eintrag.ziel.kennung}")) {
                    Text(
                        stringResource(
                            when (eintrag.zustand) {
                                UploadZustand.FEHLGESCHLAGEN -> R.string.drive_retry_action
                                UploadZustand.LAEUFT -> R.string.drive_resume_action
                                else -> R.string.drive_upload_now_action
                            },
                        ),
                    )
                }
            }
        }
    }
}
