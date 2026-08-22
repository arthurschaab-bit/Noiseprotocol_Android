package com.example.lrmprotokoll.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.example.lrmprotokoll.drive.DriveDatei
import kotlinx.coroutines.launch

const val DRIVE_FOLDER_DIALOG_TAG = "drive_folder_dialog"
const val DRIVE_CREATE_FOLDER_BUTTON_TAG = "drive_create_folder_button"
const val DRIVE_REFRESH_FOLDERS_BUTTON_TAG = "drive_refresh_folders_button"

/**
 * Interaktiver Google Drive Ordner-Management-Dialog:
 * Erlaubt das Durchsuchen, Auswählen, Erstellen und Umbenennen von Google Drive Zielordnern.
 */
@Composable
fun DriveFolderPickerDialog(
    currentFolderId: String?,
    currentFolderName: String,
    onSelectFolder: (DriveDatei) -> Unit,
    onCreateFolder: suspend (String) -> Result<DriveDatei>,
    onRenameFolder: suspend (String, String) -> Result<Unit>,
    onLoadFolders: suspend () -> Result<List<DriveDatei>>,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var folders by remember { mutableStateOf<List<DriveDatei>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var showCreateDialog by remember { mutableStateOf(false) }
    var newFolderNameInput by remember { mutableStateOf("") }
    var isCreating by remember { mutableStateOf(false) }

    var folderToRename by remember { mutableStateOf<DriveDatei?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var isRenaming by remember { mutableStateOf(false) }

    fun refreshFolders() {
        scope.launch {
            isLoading = true
            errorMessage = null
            onLoadFolders().fold(
                onSuccess = { list ->
                    folders = list
                    isLoading = false
                },
                onFailure = { error ->
                    errorMessage = error.localizedMessage ?: "Fehler beim Laden der Ordner"
                    isLoading = false
                }
            )
        }
    }

    LaunchedEffect(Unit) {
        refreshFolders()
    }

    // Sub-Dialog: Neuer Ordner erstellen
    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { if (!isCreating) showCreateDialog = false },
            title = { Text("Neuen Ordner erstellen", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "Gib den Namen für den neuen Google Drive Ordner ein:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newFolderNameInput,
                        onValueChange = { newFolderNameInput = it },
                        label = { Text("Ordnername") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isCreating
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = newFolderNameInput.trim()
                        if (name.isNotBlank()) {
                            scope.launch {
                                isCreating = true
                                onCreateFolder(name).fold(
                                    onSuccess = { created ->
                                        isCreating = false
                                        showCreateDialog = false
                                        newFolderNameInput = ""
                                        onSelectFolder(created)
                                    },
                                    onFailure = { err ->
                                        isCreating = false
                                        errorMessage = "Ordner konnte nicht erstellt werden: ${err.message}"
                                    }
                                )
                            }
                        }
                    },
                    enabled = newFolderNameInput.isNotBlank() && !isCreating
                ) {
                    if (isCreating) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text("Erstellen & Auswählen")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showCreateDialog = false },
                    enabled = !isCreating
                ) {
                    Text("Abbrechen")
                }
            }
        )
    }

    // Sub-Dialog: Ordner umbenennen
    if (folderToRename != null) {
        val target = folderToRename!!
        AlertDialog(
            onDismissRequest = { if (!isRenaming) folderToRename = null },
            title = { Text("Ordner umbenennen", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "Neuer Name für \"${target.name}\":",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = renameInput,
                        onValueChange = { renameInput = it },
                        label = { Text("Neuer Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isRenaming
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = renameInput.trim()
                        if (name.isNotBlank()) {
                            scope.launch {
                                isRenaming = true
                                onRenameFolder(target.id, name).fold(
                                    onSuccess = {
                                        isRenaming = false
                                        folderToRename = null
                                        refreshFolders()
                                    },
                                    onFailure = { err ->
                                        isRenaming = false
                                        errorMessage = "Umbenennen fehlgeschlagen: ${err.message}"
                                    }
                                )
                            }
                        }
                    },
                    enabled = renameInput.isNotBlank() && !isRenaming
                ) {
                    if (isRenaming) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text("Umbenennen")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { folderToRename = null },
                    enabled = !isRenaming
                ) {
                    Text("Abbrechen")
                }
            }
        )
    }

    // Haupt-Dialog: Ordnerauswahl & Verwaltung
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(DRIVE_FOLDER_DIALOG_TAG),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = AppIcons.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Google Drive Ordner", fontWeight = FontWeight.Bold)
                }
                IconButton(
                    onClick = { refreshFolders() },
                    modifier = Modifier.testTag(DRIVE_REFRESH_FOLDERS_BUTTON_TAG)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Aktualisieren")
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Wähle einen Zielordner für Messdaten und WAV-Aufnahmen oder verwalte bestehende Ordner.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (errorMessage != null) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = errorMessage!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            TextButton(
                                onClick = { refreshFolders() },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("Erneut versuchen")
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Lade Google Drive Ordner…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else if (folders.isEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                AppIcons.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Noch keine Ordner vorhanden",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "Erstelle jetzt einen neuen Ordner für deine Messungen.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(folders, key = { it.id }) { folder ->
                            val isSelected = folder.id == currentFolderId || folder.name == currentFolderName
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectFolder(folder) }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = if (isSelected) Icons.Default.CheckCircle else AppIcons.Folder,
                                            contentDescription = null,
                                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                text = folder.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = "ID: ${folder.id.take(8)}…",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    IconButton(
                                        onClick = {
                                            folderToRename = folder
                                            renameInput = folder.name
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Edit,
                                            contentDescription = "Umbenennen",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    newFolderNameInput = "Lärmprotokoll"
                    showCreateDialog = true
                },
                modifier = Modifier.testTag(DRIVE_CREATE_FOLDER_BUTTON_TAG)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Neuer Ordner")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Schließen")
            }
        }
    )
}
