package com.example.lrmprotokoll.report.gesamtbericht.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import com.example.lrmprotokoll.data.AppDatabase
import com.example.lrmprotokoll.report.gesamtbericht.calc.GesamtberichtAggregator
import com.example.lrmprotokoll.report.gesamtbericht.model.GesamtberichtConfig
import com.example.lrmprotokoll.report.gesamtbericht.pdf.GesamtberichtPdfGenerator
import com.example.lrmprotokoll.ui.AppIcons
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GesamtberichtDialog(
    db: AppDatabase,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var messort by remember { mutableStateOf("Messstandort") }
    var auftraggeber by remember { mutableStateOf("Dokumentation für Behörden / Vermieter") }
    var gebietsartIndex by remember { mutableIntStateOf(1) } // 0: WR (50dB), 1: WA (55dB), 2: MI (60dB)
    var zeitraumIndex by remember { mutableIntStateOf(0) } // 0: Alle Tage, 1: Letzte 7 Tage, 2: Letzte 30 Tage
    var includeWav by remember { mutableStateOf(true) }

    var isGenerating by remember { mutableStateOf(false) }
    var progressPage by remember { mutableIntStateOf(0) }
    var totalPages by remember { mutableIntStateOf(0) }
    var generatedFile by remember { mutableStateOf<File?>(null) }

    val gebiete = listOf(
        Triple("Reines Wohngebiet (WR)", 50.0, 35.0),
        Triple("Allgemeines Wohngebiet (WA)", 55.0, 40.0),
        Triple("Mischgebiet / Innenstadt (MI)", 60.0, 45.0)
    )

    Dialog(onDismissRequest = { if (!isGenerating) onDismiss() }) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Gesamtbericht (PDF)",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Nach TA Lärm & AVV Baulärm (v10)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!isGenerating) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Schließen")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (generatedFile != null) {
                    // Erfolgsmeldung & Aktionen
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDF4)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "✓ Gesamtbericht erfolgreich erstellt!",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF16A34A),
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Datei: ${generatedFile!!.name} (${generatedFile!!.length() / 1024} KB)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                openPdfFile(context, generatedFile!!)
                            }
                        ) {
                            Text("PDF öffnen")
                        }

                        FilledTonalButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                sharePdfFile(context, generatedFile!!)
                            }
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Teilen")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onDismiss
                    ) {
                        Text("Fertig")
                    }

                } else if (isGenerating) {
                    // Fortschrittsanzeige
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (totalPages > 0) "Generiere Seite $progressPage von $totalPages…" else "Aggregiere Messreihen…",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Berechne LAeq, Perzentile & SHA-256 Hashes",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    // Konfigurationsformular
                    OutlinedTextField(
                        value = messort,
                        onValueChange = { messort = it },
                        label = { Text("Messstandort / Adresse") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = auftraggeber,
                        onValueChange = { auftraggeber = it },
                        label = { Text("Auftraggeber / Zweck") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "Gebiet & Richtwerte:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("WR (50 dB)", "WA (55 dB)", "MI (60 dB)").forEachIndexed { index, label ->
                            FilterChip(
                                selected = gebietsartIndex == index,
                                onClick = { gebietsartIndex = index },
                                label = { Text(label, fontSize = 11.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "Zeitraum:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("Alle Tage", "Letzte 7 Tage", "Letzte 30 Tage").forEachIndexed { index, label ->
                            FilterChip(
                                selected = zeitraumIndex == index,
                                onClick = { zeitraumIndex = index },
                                label = { Text(label, fontSize = 11.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Audio-Belege (WAV) im Manifest",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Switch(
                            checked = includeWav,
                            onCheckedChange = { includeWav = it }
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            isGenerating = true
                            scope.launch {
                                try {
                                    val now = System.currentTimeMillis()
                                    val vonDatum = when (zeitraumIndex) {
                                        1 -> now - 7L * 24 * 3600 * 1000L
                                        2 -> now - 30L * 24 * 3600 * 1000L
                                        else -> null
                                    }

                                    val gewaehltesGebiet = gebiete[gebietsartIndex]
                                    val config = GesamtberichtConfig(
                                        messort = messort.ifBlank { "Messstandort" },
                                        auftraggeber = auftraggeber.ifBlank { "Dokumentation" },
                                        gebietsart = gewaehltesGebiet.first,
                                        richtwertTagWa = gewaehltesGebiet.second,
                                        eingreifwertTag = gewaehltesGebiet.second + 5.0,
                                        richtwertNachtWa = gewaehltesGebiet.third,
                                        eingreifwertNacht = gewaehltesGebiet.third + 5.0,
                                        vonDatum = vonDatum,
                                        bisDatum = null,
                                        includeWavClassification = includeWav
                                    )

                                    val aggregator = GesamtberichtAggregator(db)
                                    val data = aggregator.aggregiere(config)

                                    if (data.tage.isEmpty()) {
                                        Toast.makeText(context, "Keine Messdaten im gewählten Zeitraum vorhanden", Toast.LENGTH_LONG).show()
                                        isGenerating = false
                                        return@launch
                                    }

                                    val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.GERMAN).format(Date())
                                    val docsDir = context.getExternalFilesDir(null) ?: context.filesDir
                                    val targetFile = File(docsDir, "Gesamtbericht_Schallmessung_${dateStr}.pdf")

                                    val generator = GesamtberichtPdfGenerator(context)
                                    val result = generator.generierePdf(data, targetFile) { p, total ->
                                        progressPage = p
                                        totalPages = total
                                    }

                                    generatedFile = result
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Fehler bei PDF-Erstellung: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                                } finally {
                                    isGenerating = false
                                }
                            }
                        }
                    ) {
                        Icon(AppIcons.BarChart, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Gesamtbericht (PDF) generieren")
                    }
                }
            }
        }
    }
}

private fun openPdfFile(context: Context, file: File) {
    try {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Kein PDF-Viewer gefunden. Bitte PDF-App installieren.", Toast.LENGTH_LONG).show()
    }
}

private fun sharePdfFile(context: Context, file: File) {
    try {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Gesamtbericht (PDF) teilen"))
    } catch (e: Exception) {
        Toast.makeText(context, "Fehler beim Teilen: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
    }
}
