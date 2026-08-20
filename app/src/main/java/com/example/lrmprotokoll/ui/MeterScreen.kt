package com.example.lrmprotokoll.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.audio.AudioRecordingService
import com.example.lrmprotokoll.meter.ConnectionState
import com.example.lrmprotokoll.meter.GeraetePinning
import com.example.lrmprotokoll.meter.MeasurementRange
import com.example.lrmprotokoll.meter.PinningBefund
import com.example.lrmprotokoll.meter.TimeWeighting
import com.example.lrmprotokoll.meter.Weighting
import com.example.lrmprotokoll.meter.ble.BleDevice
import com.example.lrmprotokoll.meter.ble.BleScanner
import com.example.lrmprotokoll.meter.ble.Pce323Profile
import com.example.lrmprotokoll.meter.label
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val SCAN_DURATION_MS = 10_000L
private const val TAG = "MeterScreen"

/**
 * Kopplung und Live-Anzeige fuer das PCE-323 (Plan Abschnitt 9, minimale Ausbaustufe M2).
 * Reine Anzeige: keine Persistenz der Messreihe, keine Verknuepfung mit dem Aufnahme-Trigger
 * (das ist M4). Der Pegel traegt nur dann ein "dB(A)"/"dB(C)"-Label, wenn
 * [MeterFrame.modeAssumptionConfirmed] gesetzt ist - bis dahin ist die Frequenzbewertung des
 * realen Geraets nur eine Annahme, kein bestaetigtes Wissen (docs/PROTOKOLL_PCE-323.md,
 * Abschnitt 9).
 *
 * Zusaetzlich werden Bewertung, Zeitbewertung und Messbereich gespiegelt, wie sie der Decoder
 * unter dieser noch unbestaetigten Annahme interpretiert - deutlich markiert, damit ein
 * Vergleich mit der Geraeteanzeige moeglich ist und der Owner die Annahme bestaetigen oder
 * verwerfen kann.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeterScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as LaermprotokollApp).container }
    val transport = container.meterTransport
    val supervisor = container.connectionSupervisor
    val settings = container.settingsManager
    val scope = rememberCoroutineScope()

    // Die Berechtigungsabfrage selbst laeuft beim App-Start in NoiseProtocolApp; hier wird nur
    // der aktuelle Stand gelesen, um Scan/Verbinden-Buttons entsprechend zu sperren.
    val hasBluetoothPermissions = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    // Der Verbindungszustand kommt vom ConnectionSupervisor, nicht direkt vom Transport (PROMPT_M3
    // Aufgabe 3): nur der Supervisor kennt RECONNECTING/DEGRADED/FAILED, und nur er - vom
    // AudioRecordingService betrieben - ueberlebt das Schliessen dieses Screens. Die UI
    // beobachtet nur noch, sie treibt die Verbindung nicht mehr selbst.
    val connectionState by supervisor.state.collectAsState()
    val latestFrame by transport.frames.collectAsState(initial = null)

    var pairedAddress by remember { mutableStateOf(settings.meterDeviceAddress) }
    var pairedName by remember { mutableStateOf(settings.meterDeviceName) }
    var isScanning by remember { mutableStateOf(false) }
    // Geraetetest-Rueckmeldung: "Wenn ich Scannen crashed die App." - siehe scanFehlermeldung()
    // fuer die Ursache und den Grund, warum eine reine try{}finally{} (ohne catch) das nicht
    // abfing.
    var scanFehler by remember { mutableStateOf<String?>(null) }
    val foundDevices = remember { mutableStateMapOf<String, BleDevice>() }
    // Plan Abschnitt 6, Geraete-Pinning: ein Advertiser mit demselben Namen wie das gepinnte
    // Geraet, aber anderer Adresse, wird nicht kommentarlos wie jedes andere gefundene Geraet
    // angeboten - siehe GeraetePinning-KDoc.
    var verdaechtigesGeraet by remember { mutableStateOf<BleDevice?>(null) }

    // Startet (falls noetig) den Foreground Service, der den Supervisor mit dem aktuell in
    // SettingsManager gepinnten Geraet verbindet - ohne EXTRA_START_AUDIO_MONITORING, das
    // Koppeln eines Messgeraets soll nicht ungefragt die Mikrofon-Ueberwachung mitstarten.
    fun ensureConnected() {
        context.startForegroundService(Intent(context, AudioRecordingService::class.java))
    }

    fun pinne(device: BleDevice) {
        settings.meterDeviceAddress = device.address
        settings.meterDeviceName = device.name ?: device.address
        pairedAddress = device.address
        pairedName = device.name ?: device.address
        ensureConnected()
    }

    if (verdaechtigesGeraet != null) {
        val device = verdaechtigesGeraet!!
        AlertDialog(
            onDismissRequest = { verdaechtigesGeraet = null },
            title = { Text("Anderes Gerät mit gleichem Namen") },
            text = {
                Text(
                    "„${device.name}“ (${device.address}) trägt denselben Namen wie das " +
                        "gekoppelte Gerät ($pairedAddress), hat aber eine andere Adresse. " +
                        "Das kann ein zweites, echtes Gerät sein – oder ein untergeschobenes. " +
                        "Wirklich zu diesem Gerät wechseln?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pinne(device)
                    verdaechtigesGeraet = null
                }) { Text("Trotzdem koppeln") }
            },
            dismissButton = {
                TextButton(onClick = { verdaechtigesGeraet = null }) { Text("Abbrechen") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Messgerät") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                }
            )
        }
    ) { padding ->
        // Ein einziges LazyColumn statt einer Column mit verschachteltem LazyColumn fuer die
        // Geraeteliste (M7c Aufgabe 4, Bestandsaufnahme-Befund 4.2): dasselbe Muster wie
        // ProtokollDetailScreen/DiagnoseScreen - item{} fuer den festen Kopfbereich, items() fuer
        // die Geraeteliste. Ohne diese Absicherung koennen Verbindungszustand, Warnungen und die
        // Live-Pegel-Karte zusammen mehr Platz beanspruchen als der Bildschirm hoch ist, ohne
        // dass man zur Geraeteliste oder zum "Verbinden"-Button scrollen kann.
        val sortierteGefundeneGeraete = foundDevices.values.sortedByDescending { it.rssi }
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
        ) {
            item {
                val (icon, label, color) = connectionStateDisplay(connectionState)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, tint = color)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(label, style = MaterialTheme.typography.titleMedium)
                }

                // Plan Abschnitt 6: createBond() fuehrte in der M0-Aufzeichnung zu einem
                // sofortigen Disconnect (Pce323Profile.BONDING_SUPPORTED-KDoc) - ein erneuter
                // Versuch wuerde die Verbindung nur wieder gefaehrden. Die Konsequenz aus dem
                // Plan ist deshalb nicht ein Bonding-Versuch, sondern die ehrliche
                // Kennzeichnung: Sicherheit vorzutaeuschen waere schlimmer als eine
                // dokumentierte Luecke.
                if (!Pce323Profile.BONDING_SUPPORTED) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.height(16.dp).width(16.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Unverschlüsselte Verbindung – dieses Gerät unterstützt kein Bonding",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                val frame = latestFrame
                if (frame != null && connectionState == ConnectionState.STREAMING) {
                    // Die Annahme-Zuordnung darf den Pegel erst dann als "dB(A)"/"dB(C)"
                    // beschriften, wenn sie am Geraet bestaetigt ist (Review PR #15, Befund 1) -
                    // solange modeAssumptionConfirmed false ist, gilt weighting != null als
                    // "angenommen", nicht als gesichertes Wissen.
                    val confirmedWeighting = frame.weighting.takeIf { frame.modeAssumptionConfirmed }
                    Text(
                        if (confirmedWeighting != null) {
                            "${String.format("%.1f", frame.level)} dB(${weightingLabel(confirmedWeighting)})"
                        } else {
                            "${String.format("%.1f", frame.level)} dB"
                        },
                        style = MaterialTheme.typography.displayLarge
                    )
                    if (confirmedWeighting == null) {
                        Text(
                            "Frequenzbewertung unbekannt – kein bestätigtes dBA",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                if (frame.modeAssumptionConfirmed) {
                                    "Bestätigt am Gerät"
                                } else {
                                    "Annahme, unbestätigt – bitte gegen die Geräteanzeige prüfen"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (frame.modeAssumptionConfirmed) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                }
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Bewertung: ${weightingLabel(frame.weighting)}", style = MaterialTheme.typography.bodyMedium)
                            Text("Zeitbewertung: ${timeWeightingLabel(frame.timeWeighting)}", style = MaterialTheme.typography.bodyMedium)
                            Text("Messbereich: ${rangeLabel(frame.range)}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                if (pairedAddress != null) {
                    Text(
                        "Gekoppelt: ${pairedName ?: "Unbekannt"} ($pairedAddress)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { ensureConnected() },
                        enabled = hasBluetoothPermissions
                    ) {
                        Text("Verbinden")
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                Text("Neues Gerät koppeln", style = MaterialTheme.typography.titleSmall)
                if (!hasBluetoothPermissions) {
                    Text(
                        "Bluetooth-Berechtigung erforderlich",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        foundDevices.clear()
                        scanFehler = null
                        isScanning = true
                        scope.launch {
                            val scanner = BleScanner(context)
                            try {
                                withTimeoutOrNull(SCAN_DURATION_MS) {
                                    scanner.scan().collect { device ->
                                        if (!foundDevices.containsKey(device.address)) {
                                            val befund = GeraetePinning.beurteile(
                                                device.address, device.name, pairedAddress, pairedName,
                                            )
                                            if (befund == PinningBefund.VERDAECHTIG_GLEICHER_NAME) {
                                                Log.w(
                                                    TAG,
                                                    "Advertiser ${device.address} traegt denselben Namen " +
                                                        "wie das gepinnte Geraet ($pairedAddress), aber " +
                                                        "eine andere Adresse - moeglicher Spoofing-Versuch",
                                                )
                                            }
                                        }
                                        foundDevices[device.address] = device
                                    }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // Geraetetest-Rueckmeldung: "Scannen crashed die App." Ursache:
                                // BleScanner.onScanFailed() schliesst den Flow ueber
                                // close(IllegalStateException(...)) - z.B. bei
                                // SCAN_FAILED_SCANNING_TOO_FREQUENTLY, wenn manche Hersteller
                                // schnell aufeinanderfolgende Scan-Starts drosseln. Das vorherige
                                // try{}finally{} OHNE catch fing das nicht ab: die Exception lief
                                // unbehandelt aus dieser Coroutine heraus und beendete den Prozess.
                                Log.w(TAG, "Scan fehlgeschlagen", e)
                                scanFehler = scanFehlermeldung(e)
                            } finally {
                                isScanning = false
                            }
                        }
                    },
                    enabled = hasBluetoothPermissions && !isScanning,
                    modifier = Modifier.testTag(SCAN_BUTTON_TAG),
                ) {
                    Text(if (isScanning) "Suche läuft…" else "Scannen (10s)")
                }

                scanFehler?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(modifier = Modifier.height(8.dp))
            }

            itemsIndexed(sortierteGefundeneGeraete) { index, device ->
                val befund = GeraetePinning.beurteile(device.address, device.name, pairedAddress, pairedName)
                Card(
                    onClick = {
                        if (befund == PinningBefund.VERDAECHTIG_GLEICHER_NAME) {
                            verdaechtigesGeraet = device
                        } else {
                            pinne(device)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .let { if (index == sortierteGefundeneGeraete.lastIndex) it.testTag(GERAETE_LISTE_ENDE_TAG) else it },
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(device.name ?: "(ohne Namen)", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${device.address} · ${device.rssi} dBm",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (befund == PinningBefund.VERDAECHTIG_GLEICHER_NAME) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.height(16.dp).width(16.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    "Gleicher Name wie das gekoppelte Gerät, andere Adresse",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Fuer den Compose-Regressionstest (M7c Aufgabe 4): markiert die letzte Zeile der
 * Geraeteliste, damit ein Test pruefen kann, dass sie per Scroll erreichbar ist. */
const val GERAETE_LISTE_ENDE_TAG = "meter_geraete_liste_ende"

/** Fuer denselben Regressionstest: markiert den Scan-Button am Ende des festen Kopfbereichs,
 * erreichbar unabhaengig davon, ob die Geraeteliste gerade Eintraege enthaelt. */
const val SCAN_BUTTON_TAG = "meter_scan_button"

/**
 * Beschriftungen fuer die drei Annahme-Werte aus [Pce323Profile] (Bereich/Fast-Slow/A-C) - ein
 * `null` (unbekannter Bytewert) wird als solches ausgeschrieben, nie stillschweigend
 * weggelassen. Diese Zuordnung ist unbestaetigt; die Live-Anzeige in [MeterScreen] existiert
 * genau dafuer, sie am realen Geraet gegenzupruefen.
 */
private fun weightingLabel(weighting: Weighting?): String = when (weighting) {
    Weighting.A -> "A"
    Weighting.C -> "C"
    null -> "unbekannter Wert"
}

private fun timeWeightingLabel(timeWeighting: TimeWeighting?): String = when (timeWeighting) {
    TimeWeighting.FAST -> "Fast"
    TimeWeighting.SLOW -> "Slow"
    null -> "unbekannter Wert"
}

/**
 * Uebersetzt eine beim Scan aufgetretene Exception in eine fuer den Nutzer verstaendliche
 * Meldung, statt die App abstuerzen zu lassen (Geraetetest-Rueckmeldung: "Scannen crashed die
 * App"). Als reine Funktion ohne Android-/Compose-Abhaengigkeit per JVM-Test pruefbar.
 */
internal fun scanFehlermeldung(fehler: Throwable): String = when (fehler) {
    is SecurityException -> "Bluetooth-Berechtigung wurde entzogen - bitte erneut erteilen."
    else -> "Scan fehlgeschlagen: ${fehler.message ?: fehler::class.simpleName ?: "unbekannter Fehler"}"
}

private fun rangeLabel(range: MeasurementRange?): String = when (range) {
    MeasurementRange.RANGE_30_130 -> "30–130 dB"
    MeasurementRange.RANGE_30_80 -> "30–80 dB"
    MeasurementRange.RANGE_50_100 -> "50–100 dB"
    MeasurementRange.RANGE_80_130 -> "80–130 dB"
    null -> "unbekannter Wert"
}

/**
 * Verbindungszustand wird nie nur farblich kodiert (Barrierefreiheit) - immer Text und Icon.
 * Der Text kommt aus [com.example.lrmprotokoll.meter.label], damit Notification und Live-Anzeige
 * nie auseinanderlaufen; Icon und Farbe bleiben UI-lokal.
 */
@Composable
private fun connectionStateDisplay(state: ConnectionState): Triple<ImageVector, String, Color> {
    val (icon, color) = when (state) {
        ConnectionState.IDLE -> Icons.Default.Info to Color.Gray
        ConnectionState.SCANNING -> Icons.Default.Refresh to Color(0xFF1976D2)
        ConnectionState.CONNECTING,
        ConnectionState.DISCOVERING,
        ConnectionState.SUBSCRIBING -> Icons.Default.Refresh to Color(0xFF1976D2)
        ConnectionState.STREAMING -> Icons.Default.Check to Color(0xFF4CAF50)
        ConnectionState.DEGRADED -> Icons.Default.Warning to Color(0xFFFFA000)
        ConnectionState.RECONNECTING -> Icons.Default.Refresh to Color(0xFFFFA000)
        ConnectionState.DISCONNECTED -> Icons.Default.Close to Color.Gray
        ConnectionState.FAILED -> Icons.Default.Warning to Color(0xFFD32F2F)
    }
    return Triple(icon, state.label(), color)
}
