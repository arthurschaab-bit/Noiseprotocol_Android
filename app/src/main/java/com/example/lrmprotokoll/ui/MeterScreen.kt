package com.example.lrmprotokoll.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.meter.BoundDevice
import com.example.lrmprotokoll.meter.ConnectionState
import com.example.lrmprotokoll.meter.ble.BleDevice
import com.example.lrmprotokoll.meter.ble.BleScanner
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val SCAN_DURATION_MS = 10_000L

/**
 * Kopplung und Live-Anzeige fuer das PCE-323 (Plan Abschnitt 9, minimale Ausbaustufe M2).
 * Reine Anzeige: keine Persistenz der Messreihe, keine Verknuepfung mit dem Aufnahme-Trigger
 * (das ist M4). Der Pegel wird bewusst nicht als "dBA" beschriftet - die Frequenzbewertung des
 * realen Geraets ist ungeklaert (docs/PROTOKOLL_PCE-323.md, Abschnitt 7).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeterScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as LaermprotokollApp).container }
    val transport = container.meterTransport
    val settings = container.settingsManager
    val scope = rememberCoroutineScope()

    // Die Berechtigungsabfrage selbst laeuft beim App-Start in NoiseProtocolApp; hier wird nur
    // der aktuelle Stand gelesen, um Scan/Verbinden-Buttons entsprechend zu sperren.
    val hasBluetoothPermissions = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    val connectionState by transport.state.collectAsState()
    val latestFrame by transport.frames.collectAsState(initial = null)

    var pairedAddress by remember { mutableStateOf(settings.meterDeviceAddress) }
    var pairedName by remember { mutableStateOf(settings.meterDeviceName) }
    var isScanning by remember { mutableStateOf(false) }
    val foundDevices = remember { mutableStateMapOf<String, BleDevice>() }

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
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
        ) {
            val (icon, label, color) = connectionStateDisplay(connectionState)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = color)
                Spacer(modifier = Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.titleMedium)
            }

            Spacer(modifier = Modifier.height(16.dp))

            val frame = latestFrame
            if (frame != null && connectionState == ConnectionState.STREAMING) {
                Text(
                    "${String.format("%.1f", frame.level)} dB",
                    style = MaterialTheme.typography.displayLarge
                )
                Text(
                    "Frequenzbewertung unbekannt – kein bestätigtes dBA",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
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
                    onClick = {
                        val address = pairedAddress ?: return@Button
                        scope.launch { transport.connect(BoundDevice(address, pairedName ?: address)) }
                    },
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
                    isScanning = true
                    scope.launch {
                        val scanner = BleScanner(context)
                        try {
                            withTimeoutOrNull(SCAN_DURATION_MS) {
                                scanner.scan().collect { device -> foundDevices[device.address] = device }
                            }
                        } finally {
                            isScanning = false
                        }
                    }
                },
                enabled = hasBluetoothPermissions && !isScanning
            ) {
                Text(if (isScanning) "Suche läuft…" else "Scannen (10s)")
            }

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(foundDevices.values.sortedByDescending { it.rssi }) { device ->
                    Card(
                        onClick = {
                            settings.meterDeviceAddress = device.address
                            settings.meterDeviceName = device.name ?: device.address
                            pairedAddress = device.address
                            pairedName = device.name ?: device.address
                            scope.launch {
                                transport.connect(BoundDevice(device.address, device.name ?: device.address))
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(device.name ?: "(ohne Namen)", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${device.address} · ${device.rssi} dBm",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Verbindungszustand wird nie nur farblich kodiert (Barrierefreiheit) - immer Text und Icon. */
@Composable
private fun connectionStateDisplay(state: ConnectionState): Triple<ImageVector, String, Color> {
    return when (state) {
        ConnectionState.IDLE -> Triple(Icons.Default.Info, "Nicht verbunden", Color.Gray)
        ConnectionState.SCANNING -> Triple(Icons.Default.Refresh, "Suche…", Color(0xFF1976D2))
        ConnectionState.CONNECTING,
        ConnectionState.DISCOVERING,
        ConnectionState.SUBSCRIBING -> Triple(Icons.Default.Refresh, "Verbinde…", Color(0xFF1976D2))
        ConnectionState.STREAMING -> Triple(Icons.Default.Check, "Verbunden", Color(0xFF4CAF50))
        ConnectionState.DEGRADED -> Triple(Icons.Default.Warning, "Instabil", Color(0xFFFFA000))
        ConnectionState.RECONNECTING -> Triple(Icons.Default.Refresh, "Verbinde erneut…", Color(0xFFFFA000))
        ConnectionState.DISCONNECTED -> Triple(Icons.Default.Close, "Getrennt", Color.Gray)
        ConnectionState.FAILED -> Triple(Icons.Default.Warning, "Fehlgeschlagen", Color(0xFFD32F2F))
    }
}
