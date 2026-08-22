package com.example.lrmprotokoll.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val QUICK_EVENT_CATEGORIES = listOf(
    "🔨 Hämmern",
    "🪚 Bohren / Werkzeug",
    "🎵 Musik / Bass",
    "👣 Trittschall / Poltern",
    "🗣️ Sprache / Geschrei",
    "🚗 Straßenlärm",
    "🐶 Hundegebell",
    "❓ Sonstiges"
)

/**
 * 1-Klick Dialog zur schnellen Markierung und Kategorisierung eines Lärmereignisses
 * während einer aktiven Messung (UX-Briefing Punkte 13 & 14).
 */
@Composable
fun QuickEventTagDialog(
    currentDb: Double?,
    onDismiss: () -> Unit,
    onSave: (category: String, note: String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Lärmereignis markieren",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Schließen")
                }
            }
        },
        text = {
            QuickEventTagContent(
                currentDb = currentDb,
                onSave = onSave
            )
        },
        confirmButton = {}
    )
}

@Composable
fun QuickEventTagContent(
    currentDb: Double?,
    onSave: (category: String, note: String) -> Unit
) {
    var selectedCategory by remember { mutableStateOf(QUICK_EVENT_CATEGORIES.first()) }
    var customNote by remember { mutableStateOf("") }
    val timeFormatted = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()) }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Factual info banner
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Zeitpunkt: $timeFormatted · Pegel: ${currentDb?.let { "%.1f dB".format(Locale.US, it) } ?: "-- dB"}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "Kategorie wählen:",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(QUICK_EVENT_CATEGORIES) { cat ->
                val isSelected = cat == selectedCategory
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    border = if (isSelected) ButtonDefaults.outlinedButtonBorder else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedCategory = cat }
                ) {
                    Text(
                        text = cat,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        OutlinedTextField(
            value = customNote,
            onValueChange = { customNote = it },
            label = { Text("Zusatznotiz (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(14.dp))
        Button(
            onClick = { onSave(selectedCategory, customNote.trim()) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Ereignis jetzt speichern")
        }
    }
}
