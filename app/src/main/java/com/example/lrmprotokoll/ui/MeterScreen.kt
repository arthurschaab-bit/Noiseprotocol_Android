package com.example.lrmprotokoll.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.lrmprotokoll.meter.ble.BluetoothPermissions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
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
const val METER_DISCONNECT_CONFIRM_DIALOG_TAG = "meter_disconnect_confirm_dialog"

/**
 * Kopplung und Live-Anzeige fuer das PCE-323 (Plan Abschnitt 9, minimale Ausbaustufe M2).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeterScreen(
    onBack: () -> Unit,
    onOpenDrawer: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as LaermprotokollApp).container }
    val transport = container.meterTransport
    val supervisor = container.connectionSupervisor
    val settings = container.settingsManager
    val scope = rememberCoroutineScope()

    val lifecycleOwner = LocalLifecycleOwner.current
    var hasBluetoothPermissions by remember { mutableStateOf(BluetoothPermissions.hasPermissions(context)) }
    var isLocationEnabled by remember { mutableStateOf(BluetoothPermissions.isLocationEnabled(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasBluetoothPermissions = BluetoothPermissions.hasPermissions(context)
                isLocationEnabled = BluetoothPermissions.isLocationEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val connectionState by supervisor.state.collectAsState()
    val latestFrame by transport.frames.collectAsState(initial = null)

    var pairedAddress by remember { mutableStateOf(settings.meterDeviceAddress) }
    var pairedName by remember { mutableStateOf(settings.meterDeviceName) }
    var isScanning by remember { mutableStateOf(false) }
    var scanFehler by remember { mutableStateOf<String?>(null) }
    val foundDevices = remember { mutableStateMapOf<String, BleDevice>() }
    var verdaechtigesGeraet by remember { mutableStateOf<BleDevice?>(null) }
    var showDisconnectConfirm by remember { mutableStateOf(false) }

    fun ensureConnected() {
        context.startForegroundService(Intent(context, AudioRecordingService::class.java))
    }

    fun starteScan() {
        if (BluetoothPermissions.isLocationRequiredForScan() && !BluetoothPermissions.isLocationEnabled(context)) {
            scanFehler = "Standortdienste (GPS) müssen am Gerät aktiviert sein, um Bluetooth-Geräte zu finden."
            return
        }
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
                                        "wie das gepinnte Geraet ($pairedAddress), aber eine andere Adresse",
                                )
                            }
                        }
                        foundDevices[device.address] = device
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Scan fehlgeschlagen", e)
                scanFehler = scanFehlermeldung(e)
            } finally {
                isScanning = false
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasBluetoothPermissions = BluetoothPermissions.hasPermissions(context)
        isLocationEnabled = BluetoothPermissions.isLocationEnabled(context)
        if (hasBluetoothPermissions) starteScan()
    }

    fun requestPermissionsUndScanne() {
        if (hasBluetoothPermissions) starteScan()
        else permissionLauncher.launch(BluetoothPermissions.requiredPermissions())
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
            title = { Text("Mögliches Ersatzgerät gefunden") },
            text = {
                Text(
                    "Das gefundene Gerät '${device.name}' hat die Adresse ${device.address}. " +
                        "Gepinnt ist bisher ${pairedAddress}. Möchten Sie die Bindung auf das neue Gerät übertragen?"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pinne(device)
                        verdaechtigesGeraet = null
                    },
                    modifier = Modifier.testTag("dialog_spoofing_confirm")
                ) { Text("Trotzdem koppeln") }
            },
            dismissButton = {
                TextButton(
                    onClick = { verdaechtigesGeraet = null },
                    modifier = Modifier.testTag("dialog_spoofing_dismiss")
                ) { Text("Abbrechen") }
            },
        )
    }

    if (showDisconnectConfirm) {
        AlertDialog(
            modifier = Modifier.testTag(METER_DISCONNECT_CONFIRM_DIALOG_TAG),
            onDismissRequest = { showDisconnectConfirm = false },
            title = { Text("Bluetooth-Verbindung beenden?") },
            text = {
                Text(
                    "Das PCE-323 wird getrennt und aus der App-Kopplung entfernt. " +
                        "Eine laufende Mikrofon-/WAV-Erfassung kann weiterlaufen, kalibrierte PCE-Werte aber nicht."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        settings.meterDeviceAddress = null
                        settings.meterDeviceName = null
                        pairedAddress = null
                        pairedName = null
                        supervisor.stop()
                        showDisconnectConfirm = false
                    }
                ) { Text("PCE trennen") }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectConfirm = false }) { Text("Abbrechen") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_meter)) },
                navigationIcon = {
                    if (onOpenDrawer != null) {
                        IconButton(onClick = onOpenDrawer, modifier = Modifier.size(48.dp).testTag("btn_navigation_drawer")) {
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
                        state = connectionState,
                        deviceName = pairedName,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            )
        }
    ) { padding ->
        val sortierteGefundeneGeraete = remember(foundDevices.toMap(), pairedAddress) {
            sortiereGefundeneGeraete(foundDevices.values, pairedAddress)
        }
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
                            stringResource(R.string.meter_unencrypted_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                val frame = latestFrame
                if (frame != null && connectionState == ConnectionState.STREAMING) {
                    val confirmedWeighting = frame.weighting.takeIf { frame.modeAssumptionConfirmed }
                    Text(
                        if (confirmedWeighting != null) {
                            "${String.format("%.1f", frame.level)} dB(${weightingLabel(confirmedWeighting)})"
                        } else {
                            "${String.format("%.1f", frame.level)} dB"
                        },
                        style = MaterialTheme.typography.displayLarge,
                        modifier = Modifier
                            .testTag("live_meter_level_display")
                            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                    )
                    if (confirmedWeighting == null) {
                        Text(
                            stringResource(R.string.meter_unknown_weighting),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.testTag("card_meter_parameters")
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                if (frame.modeAssumptionConfirmed) {
                                    stringResource(R.string.meter_confirmed_on_device)
                                } else {
                                    stringResource(R.string.meter_unconfirmed_warning)
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (frame.modeAssumptionConfirmed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(stringResource(R.string.meter_weighting_value, weightingLabel(frame.weighting)), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.meter_time_weighting_value, timeWeightingLabel(frame.timeWeighting)), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.meter_range_value, rangeLabel(frame.range)), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                if (pairedAddress != null) {
                    Text(
                        stringResource(R.string.meter_paired_info, pairedName ?: "Unbekannt", pairedAddress ?: ""),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                if (hasBluetoothPermissions) ensureConnected()
                                else permissionLauncher.launch(BluetoothPermissions.requiredPermissions())
                            },
                            modifier = Modifier.testTag("btn_meter_connect")
                        ) {
                            Text(stringResource(R.string.meter_action_connect))
                        }
                        OutlinedButton(
                            onClick = { showDisconnectConfirm = true },
                            modifier = Modifier.testTag("btn_meter_unpair")
                        ) {
                            Text(stringResource(R.string.meter_action_unpair))
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                Text(stringResource(R.string.meter_pair_new_title), style = MaterialTheme.typography.titleSmall)
                if (!hasBluetoothPermissions) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                stringResource(R.string.permission_bluetooth_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.permission_bluetooth_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { permissionLauncher.launch(BluetoothPermissions.requiredPermissions()) },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text(stringResource(R.string.permission_grant_button))
                            }
                        }
                    }
                } else if (BluetoothPermissions.isLocationRequiredForScan() && !isLocationEnabled) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "Standortdienste (GPS) deaktiviert",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Auf Android 10/11 kann die Bluetooth-Suche nach dem PCE-323 nur ausgeführt werden, wenn die Standortdienste (GPS) aktiviert sind.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { context.startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Standort aktivieren")
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { requestPermissionsUndScanne() },
                    enabled = !isScanning,
                    modifier = Modifier.testTag(SCAN_BUTTON_TAG),
                ) {
                    Text(if (isScanning) stringResource(R.string.meter_scanning) else stringResource(R.string.meter_scan_button))
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
                        if (befund == PinningBefund.VERDAECHTIG_GLEICHER_NAME) verdaechtigesGeraet = device
                        else pinne(device)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .testTag("card_ble_device_${device.address}"),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(device.name ?: "(ohne Namen)", style = MaterialTheme.typography.bodyLarge)
                        Text("${device.address} · ${device.rssi} dBm", style = MaterialTheme.typography.bodySmall)
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
                                    stringResource(R.string.meter_spoof_warning),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
                if (index == sortierteGefundeneGeraete.lastIndex) {
                    Spacer(modifier = Modifier.height(1.dp).testTag(GERAETE_LISTE_ENDE_TAG))
                }
            }
        }
    }
}

const val GERAETE_LISTE_ENDE_TAG = "meter_geraete_liste_ende"
const val SCAN_BUTTON_TAG = "meter_scan_button"

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

internal fun sortiereGefundeneGeraete(devices: Collection<BleDevice>, pairedAddress: String?): List<BleDevice> {
    return devices.sortedWith(
        compareByDescending<BleDevice> { it.address == pairedAddress }
            .thenBy { it.name.isNullOrBlank() }
            .thenBy { it.name ?: "" }
            .thenBy { it.address }
    )
}
