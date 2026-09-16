package com.example.lrmprotokoll.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.lrmprotokoll.meter.ble.BluetoothPermissions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.meter.GeraetePinning
import com.example.lrmprotokoll.meter.PinningBefund
import com.example.lrmprotokoll.meter.ble.BleDevice
import com.example.lrmprotokoll.meter.ble.BleScanner
import com.example.lrmprotokoll.meter.label
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val SCAN_DURATION_MS = 10_000L
private const val TAG = "MeterControlCard"

/**
 * Dialog zum Scannen und Auswählen von Bluetooth-Messgeräten mit stabiler Sortierung.
 */
@Composable
fun MeterPairingDialog(
    pairedAddress: String?,
    pairedName: String?,
    onDeviceSelected: (BleDevice) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val foundDevices = remember { mutableStateMapOf<String, BleDevice>() }
    var isScanning by remember { mutableStateOf(false) }
    var scanFehler by remember { mutableStateOf<String?>(null) }
    var verdaechtigesGeraet by remember { mutableStateOf<BleDevice?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    var hasBluetoothPermissions by remember {
        mutableStateOf(BluetoothPermissions.hasPermissions(context))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasBluetoothPermissions = BluetoothPermissions.hasPermissions(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun starteScan() {
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
                Log.w(TAG, "Scan fehlgeschlagen", e)
                scanFehler = scanFehlermeldung(e)
            } finally {
                isScanning = false
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        hasBluetoothPermissions = BluetoothPermissions.hasPermissions(context)
        if (hasBluetoothPermissions) {
            starteScan()
        }
    }

    // Beim Öffnen automatisch Scan starten, wenn Berechtigungen vorhanden sind
    DisposableEffect(Unit) {
        if (hasBluetoothPermissions) {
            starteScan()
        }
        onDispose { }
    }

    val sortierteGeraete = remember(foundDevices.toMap(), pairedAddress) {
        foundDevices.values.sortedWith(
            compareByDescending<BleDevice> { it.address == pairedAddress }
                .thenBy { it.name.isNullOrBlank() }
                .thenBy { it.name ?: "" }
                .thenBy { it.address }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("PCE-323 koppeln")
                if (isScanning) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(
                        onClick = {
                            if (hasBluetoothPermissions) starteScan()
                            else permissionLauncher.launch(BluetoothPermissions.requiredPermissions())
                        }
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Erneut scannen")
                    }
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (!hasBluetoothPermissions) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Bluetooth-Berechtigung erforderlich",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Um das PCE-323 zu finden, wird die Bluetooth-Berechtigung benötigt.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    permissionLauncher.launch(BluetoothPermissions.requiredPermissions())
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Berechtigung erteilen")
                            }
                        }
                    }
                }

                scanFehler?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (sortierteGeraete.isEmpty() && isScanning) {
                    Text(
                        "Suche nach Bluetooth-Geräten in der Nähe…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (sortierteGeraete.isEmpty() && !isScanning) {
                    Text(
                        "Kein Bluetooth-Gerät gefunden. Stelle sicher, dass das PCE-323 eingeschaltet und Bluetooth aktiv ist.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().height(260.dp)) {
                        itemsIndexed(sortierteGeraete) { index, device ->
                            val befund = GeraetePinning.beurteile(device.address, device.name, pairedAddress, pairedName)
                            val isCurrent = device.address == pairedAddress
                            Card(
                                onClick = {
                                    if (befund == PinningBefund.VERDAECHTIG_GLEICHER_NAME) {
                                        verdaechtigesGeraet = device
                                    } else {
                                        onDeviceSelected(device)
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                colors = if (isCurrent) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                                else CardDefaults.cardColors()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = device.name ?: "(Unbekannt)",
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                                        )
                                        if (isCurrent) {
                                            Text(
                                                text = "Gekoppelt",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                    Text(
                                        text = "${device.address} · ${device.rssi} dBm",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Schließen")
            }
        }
    )

    verdaechtigesGeraet?.let { device ->
        AlertDialog(
            onDismissRequest = { verdaechtigesGeraet = null },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Neues Gerät mit bekanntem Namen?") },
            text = {
                Text(
                    "Dieses Gerät heißt wie dein bisheriges (${device.name}), hat aber die neue " +
                        "Adresse ${device.address} (bisher: $pairedAddress).\n\n" +
                        "Wenn du ein neues PCE-323 verbindest, bestätige die Kopplung. " +
                        "In fremder Umgebung könnte es sich um ein anderes Gerät handeln.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val d = device
                        verdaechtigesGeraet = null
                        onDeviceSelected(d)
                    },
                ) {
                    Text("Trotzdem koppeln")
                }
            },
            dismissButton = {
                TextButton(onClick = { verdaechtigesGeraet = null }) {
                    Text("Abbrechen")
                }
            },
        )
    }
}
