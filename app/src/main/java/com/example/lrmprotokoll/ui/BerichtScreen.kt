package com.example.lrmprotokoll.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.report.GesamtberichtExport
import com.example.lrmprotokoll.report.GesamtberichtStammdaten
import com.example.lrmprotokoll.report.PeriodenBerichtExport
import com.example.lrmprotokoll.report.ermittleGesamtbericht
import com.example.lrmprotokoll.report.ermittlePeriodenBericht
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Reiter "Bericht" - Nachfolger des Zeitraum-/Gesamtbericht-Dialogs, der vorher unter Daten
 * (ehemals Protokoll) hinter dem Kalender-Icon lag. Der Dialog selbst (Presets, Umschalter
 * Zeitraumbericht/Gesamtbericht) ist unveraendert uebernommen; nur der Einstiegspunkt ist jetzt
 * ein eigener Hauptreiter statt eines TopAppBar-Icons, als Grundlage fuer den geplanten
 * High-End-Bericht (Chaquopy, siehe docs).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BerichtScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as LaermprotokollApp).container }
    val db = container.database
    val scope = rememberCoroutineScope()
    val periodenExport = remember { PeriodenBerichtExport(context) }
    val gesamtberichtExport = remember { GesamtberichtExport(context) }

    var zeigeZeitraumDialog by remember { mutableStateOf(false) }
    var zeitraumWirdErstellt by remember { mutableStateOf(false) }
    var zeigeMenue by remember { mutableStateOf(false) }
    // Owner-Anfrage 09.09.2026: derselbe Zeitraum-Dialog soll wahlweise den knappen
    // Zeitraumbericht (bisher) oder den vollstaendigen Gesamtbericht mit Geraete-/
    // Messaufbau-/Randbedingungsangaben aus den Einstellungen erzeugen.
    var alsGesamtbericht by remember { mutableStateOf(false) }

    fun erstelleUndTeileZeitraumbericht(von: Long, bis: Long) {
        zeitraumWirdErstellt = true
        scope.launch {
            if (alsGesamtbericht) {
                val datei = withContext(Dispatchers.IO) {
                    val bericht = ermittleGesamtbericht(db, von, bis)
                    val stammdaten = GesamtberichtStammdaten.ausVerlauf(db.stammdatenVerlaufDao())
                    gesamtberichtExport.exportierePdf(bericht, stammdaten, "Lärmprotokoll – Gesamtbericht")
                }
                gesamtberichtExport.teilen(datei)
            } else {
                val datei = withContext(Dispatchers.IO) {
                    val bericht = ermittlePeriodenBericht(db, von, bis)
                    periodenExport.exportierePdf(bericht, "Lärmprotokoll – Zeitraumbericht")
                }
                periodenExport.teilen(datei)
            }
            zeitraumWirdErstellt = false
            zeigeZeitraumDialog = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.nav_report),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp).testTag("btn_bericht_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { zeigeMenue = true }, modifier = Modifier.testTag("btn_bericht_menu")) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_menu))
                        }
                        DropdownMenu(expanded = zeigeMenue, onDismissRequest = { zeigeMenue = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.nav_settings)) },
                                onClick = {
                                    zeigeMenue = false
                                    onOpenSettings()
                                },
                                modifier = Modifier.testTag("menu_item_bericht_settings"),
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.bericht_intro_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.bericht_intro_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = { zeigeZeitraumDialog = true },
                modifier = Modifier.fillMaxWidth().testTag("btn_period_report"),
            ) {
                Text(stringResource(R.string.period_report_action))
            }
        }
    }

    if (zeigeZeitraumDialog) {
        AlertDialog(
            onDismissRequest = { if (!zeitraumWirdErstellt) zeigeZeitraumDialog = false },
            title = { Text(stringResource(R.string.period_report_dialog_title)) },
            text = {
                if (zeitraumWirdErstellt) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.period_report_generating))
                    }
                } else {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().testTag("switch_row_gesamtbericht"),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.period_report_full_toggle))
                                Text(
                                    stringResource(R.string.period_report_full_toggle_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = alsGesamtbericht,
                                onCheckedChange = { alsGesamtbericht = it },
                                modifier = Modifier.testTag("switch_gesamtbericht"),
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = {
                            val bis = System.currentTimeMillis()
                            erstelleUndTeileZeitraumbericht(bis - 7L * 24 * 60 * 60 * 1000, bis)
                        }, modifier = Modifier.testTag("btn_period_preset_7d")) { Text(stringResource(R.string.period_report_preset_7_days)) }
                        TextButton(onClick = {
                            val bis = System.currentTimeMillis()
                            erstelleUndTeileZeitraumbericht(bis - 30L * 24 * 60 * 60 * 1000, bis)
                        }, modifier = Modifier.testTag("btn_period_preset_30d")) { Text(stringResource(R.string.period_report_preset_30_days)) }
                        TextButton(onClick = {
                            val bis = System.currentTimeMillis()
                            val von = Calendar.getInstance().apply {
                                set(Calendar.DAY_OF_MONTH, 1)
                                set(Calendar.HOUR_OF_DAY, 0)
                                set(Calendar.MINUTE, 0)
                                set(Calendar.SECOND, 0)
                                set(Calendar.MILLISECOND, 0)
                            }.timeInMillis
                            erstelleUndTeileZeitraumbericht(von, bis)
                        }, modifier = Modifier.testTag("btn_period_preset_month")) { Text(stringResource(R.string.period_report_preset_this_month)) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { zeigeZeitraumDialog = false }, enabled = !zeitraumWirdErstellt, modifier = Modifier.testTag("btn_period_dialog_cancel")) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}
