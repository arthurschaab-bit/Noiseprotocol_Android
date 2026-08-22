package com.example.lrmprotokoll.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.lrmprotokoll.data.DriveDailyFileEntity
import com.example.lrmprotokoll.data.DriveSyncState
import com.example.lrmprotokoll.ui.theme.statusColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val DRIVE_STATUS_CARD_TAG = "drive_status_card"
const val DRIVE_SYNC_NOW_BUTTON_TAG = "drive_sync_now_button"
const val DRIVE_CONNECT_BUTTON_TAG = "drive_connect_button"
const val DRIVE_DISCONNECT_BUTTON_TAG = "drive_disconnect_button"

/**
 * Dedizierte, übersichtliche Status- und Steuerkarte für Google Drive (PR 2).
 * Zeigt das angemeldete Konto, den Verbindungs-/Sync-Status, den Zielordner,
 * den letzten Upload und bietet direkte Aktionen (Sofort-Sync, An-/Abmelden, Ordnerwahl).
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
    var showFolderEdit by remember { mutableStateOf(false) }
    var editedFolderName by remember(folderName) { mutableStateOf(folderName) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag(DRIVE_STATUS_CARD_TAG),
        colors = CardDefaults.cardColors(
            containerColor = when {
                hasError -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                isConnected && syncEnabled -> MaterialTheme.colorScheme.surfaceVariant
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Kopfzeile: Titel & Status-Badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when {
                            hasError -> Icons.Default.Warning
                            isConnected && syncEnabled -> Icons.Default.Check
                            else -> Icons.Default.Refresh
                        },
                        contentDescription = null,
                        tint = when {
                            hasError -> MaterialTheme.colorScheme.error
                            isConnected && syncEnabled -> MaterialTheme.colorScheme.statusColors.connected
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Google Drive Sync",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = when {
                        hasError -> MaterialTheme.colorScheme.errorContainer
                        isConnected && syncEnabled -> Color(0xFFE8F5E9)
                        isConnected -> Color(0xFFFFF3E0)
                        else -> Color(0xFFF5F5F5)
                    }
                ) {
                    Text(
                        text = when {
                            hasError -> "Gestört"
                            isConnected && syncEnabled -> "Aktiv"
                            isConnected -> "Pausiert"
                            else -> "Nicht verbunden"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            hasError -> MaterialTheme.colorScheme.error
                            isConnected && syncEnabled -> Color(0xFF2E7D32)
                            isConnected -> Color(0xFFE65100)
                            else -> Color(0xFF616161)
                        },
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
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

            // Ordner-Information & Anpassung
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Ordner: $folderName" + (if (folderId != null) " (ID: ${folderId.take(8)}…)" else " (noch nicht angelegt)"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = { showFolderEdit = !showFolderEdit },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Text(if (showFolderEdit) "Abbrechen" else "Ändern", style = MaterialTheme.typography.labelSmall)
                }
            }

            AnimatedVisibility(visible = showFolderEdit) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    OutlinedTextField(
                        value = editedFolderName,
                        onValueChange = { editedFolderName = it },
                        label = { Text("Neuer Ordnername") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = {
                            onUpdateFolderName(editedFolderName.trim())
                            showFolderEdit = false
                        },
                        enabled = editedFolderName.isNotBlank() && editedFolderName != folderName,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Ordner übernehmen")
                    }
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
                            .weight(0.7f)
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
}
