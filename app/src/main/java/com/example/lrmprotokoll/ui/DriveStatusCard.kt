package com.example.lrmprotokoll.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.lrmprotokoll.data.DriveDailyFileEntity
import com.example.lrmprotokoll.data.DriveSyncState
import com.example.lrmprotokoll.ui.components.NoiseCard
import com.example.lrmprotokoll.ui.components.StatusPill
import com.example.lrmprotokoll.ui.components.StatusPillType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val DRIVE_STATUS_CARD_TAG = "drive_status_card"
const val DRIVE_SYNC_NOW_BUTTON_TAG = "drive_sync_now_button"
const val DRIVE_CONNECT_BUTTON_TAG = "drive_connect_button"
const val DRIVE_DISCONNECT_BUTTON_TAG = "drive_disconnect_button"

/**
 * Dedizierte, übersichtliche Status- und Steuerkarte für Google Drive.
 * Vollständig im OLED-Dark-Theme gehalten mit Pop-up für exakte Ordnerauswahl.
 */
@Composable
fun DriveStatusCard(
    googleAccountEmail: String?,
    googleAccountName: String?,
    syncEnabled: Boolean,
    folderName: String,
    folderId: String?,
    isFolderBlocked: Boolean,
    consecutiveFailures: Int,
    lastSuccessAt: Long,
    lastMessage: String?,
    latestDailyFile: DriveDailyFileEntity?,
    isSyncing: Boolean,
    onToggleSync: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
    onConnectGoogle: () -> Unit,
    onDisconnectGoogle: () -> Unit,
    onUpdateFolderName: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isConnected = !googleAccountEmail.isNullOrBlank() && !folderId.isNullOrBlank()
    val hasError = isFolderBlocked || consecutiveFailures > 0 || latestDailyFile?.state == DriveSyncState.FAILED
    var showFolderDialog by remember { mutableStateOf(false) }
    var inputFolderName by remember(folderName) { mutableStateOf(folderName) }

    if (showFolderDialog) {
        AlertDialog(
            onDismissRequest = { showFolderDialog = false },
            title = { Text("Google Drive Zielordner", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "Gib den exakten Namen des Zielordners in deinem Google Drive an. Wenn der Ordner noch nicht existiert, wird er automatisch angelegt.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = inputFolderName,
                        onValueChange = { inputFolderName = it },
                        label = { Text("Ordnername") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = inputFolderName.trim()
                        if (trimmed.isNotBlank()) {
                            onUpdateFolderName(trimmed)
                            showFolderDialog = false
                        }
                    },
                    enabled = inputFolderName.isNotBlank()
                ) {
                    Text("Speichern & Synchronisieren")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFolderDialog = false }) {
                    Text("Abbrechen")
                }
            }
        )
    }

    NoiseCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag(DRIVE_STATUS_CARD_TAG),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        // Kopfzeile: Titel & Status-Badge
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Google Drive Sync",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            val pillType = when {
                hasError -> StatusPillType.WARNING
                isConnected && syncEnabled -> StatusPillType.CONNECTED
                isConnected -> StatusPillType.IDLE
                else -> StatusPillType.NEUTRAL
            }
            val pillText = when {
                hasError -> "Gestört"
                isConnected && syncEnabled -> "Aktiv"
                isConnected -> "Pausiert"
                else -> "Nicht verbunden"
            }
            StatusPill(text = pillText, type = pillType)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Google-Konto Information
        if (!googleAccountEmail.isNullOrBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.AccountCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (!googleAccountName.isNullOrBlank()) "$googleAccountName ($googleAccountEmail)" else googleAccountEmail,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        // Ordner-Information & Pop-up-Auswahl
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Ordner: $folderName" + (if (folderId != null) " (ID: ${folderId.take(8)}…)" else " (noch nicht angelegt)"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = {
                    inputFolderName = folderName
                    showFolderDialog = true
                },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
            ) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Ordner wählen", style = MaterialTheme.typography.labelSmall)
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Letzter Upload & Status-Meldung
        val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
        val lastSuccessText = if (lastSuccessAt > 0L) {
            dateFormat.format(Date(lastSuccessAt))
        } else {
            "Noch kein Upload erfolgt"
        }

        Text(
            text = "Letzter erfolgreicher Sync: $lastSuccessText",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (!lastMessage.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Status: $lastMessage",
                style = MaterialTheme.typography.bodySmall,
                color = if (hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (hasError) FontWeight.Bold else FontWeight.Normal
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(10.dp))

        // Sync-Aktivierungsschalter (nur wenn verbunden)
        if (isConnected) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Automatischer 30-Minuten-Upload",
                    style = MaterialTheme.typography.bodyMedium
                )
                Switch(
                    checked = syncEnabled,
                    onCheckedChange = onToggleSync
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        // Aktions-Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isConnected) {
                Button(
                    onClick = onSyncNow,
                    enabled = !isSyncing,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                        .testTag(DRIVE_SYNC_NOW_BUTTON_TAG)
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Wird hochgeladen…")
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Jetzt synchronisieren")
                    }
                }

                OutlinedButton(
                    onClick = onDisconnectGoogle,
                    modifier = Modifier
                        .weight(0.6f)
                        .heightIn(min = 44.dp)
                        .testTag(DRIVE_DISCONNECT_BUTTON_TAG)
                ) {
                    Text("Trennen")
                }
            } else {
                Button(
                    onClick = onConnectGoogle,
                    enabled = !isSyncing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .testTag(DRIVE_CONNECT_BUTTON_TAG)
                ) {
                    Icon(Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Mit Google Drive verbinden")
                }
            }
        }
    }
}
