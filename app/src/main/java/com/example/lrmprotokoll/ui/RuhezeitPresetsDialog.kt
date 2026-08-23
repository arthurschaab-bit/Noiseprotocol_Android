package com.example.lrmprotokoll.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.lrmprotokoll.ui.theme.TechBluePrimary

data class WohnraumPreset(
    val id: String,
    val titel: String,
    val untertitel: String,
    val tagGrenzwertDb: Float,
    val nachtGrenzwertDb: Float,
    val icon: ImageVector,
    val normHinweis: String,
)

val WOHNRAUM_PRESETS = listOf(
    WohnraumPreset(
        id = "WR",
        titel = "Reines Wohngebiet (WR)",
        untertitel = "Wohngebäude ohne störendes Gewerbe",
        tagGrenzwertDb = 50f,
        nachtGrenzwertDb = 35f,
        icon = Icons.Default.Home,
        normHinweis = "TA Lärm 6.1 d)",
    ),
    WohnraumPreset(
        id = "WA",
        titel = "Allgemeines Wohngebiet (WA / WS)",
        untertitel = "Wohnviertel & Kleinsiedlungsgebiete",
        tagGrenzwertDb = 55f,
        nachtGrenzwertDb = 40f,
        icon = Icons.Default.LocationOn,
        normHinweis = "TA Lärm 6.1 e)",
    ),
    WohnraumPreset(
        id = "MI",
        titel = "Misch- & Kerngebiet (MI / MK / MD)",
        untertitel = "Wohnen und Gewerbe gemischt",
        tagGrenzwertDb = 60f,
        nachtGrenzwertDb = 45f,
        icon = Icons.Default.Place,
        normHinweis = "TA Lärm 6.1 c)",
    ),
    WohnraumPreset(
        id = "GE",
        titel = "Gewerbegebiet (GE)",
        untertitel = "Überwiegend gewerbliche Nutzung",
        tagGrenzwertDb = 65f,
        nachtGrenzwertDb = 50f,
        icon = Icons.Default.Build,
        normHinweis = "TA Lärm 6.1 b)",
    ),
    WohnraumPreset(
        id = "INNEN",
        titel = "Innenräume (Wohn-/Schlafraum)",
        untertitel = "Bei geschlossenen Fenstern",
        tagGrenzwertDb = 40f,
        nachtGrenzwertDb = 30f,
        icon = Icons.Default.Star,
        normHinweis = "DIN 4109 / VDI 2058",
    ),
    WohnraumPreset(
        id = "KUR",
        titel = "Kurgebiet & Krankenhäuser",
        untertitel = "Besonders schutzbedürftig",
        tagGrenzwertDb = 45f,
        nachtGrenzwertDb = 35f,
        icon = Icons.Default.Favorite,
        normHinweis = "TA Lärm 6.1 f)",
    ),
)

@Composable
fun RuhezeitPresetsDialog(
    aktuelleNachtSchwelle: Float,
    onDismissRequest: () -> Unit,
    onPresetSelected: (nachtDb: Float, tagDb: Float?) -> Unit,
) {
    var auchTagSchwelleUebernehmen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Column {
                Text(
                    text = "Grenzwerte nach Wohnraum",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Richtwerte gemäß TA Lärm & DIN 4109",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(WOHNRAUM_PRESETS) { preset ->
                        val istGewaehlt = preset.nachtGrenzwertDb == aktuelleNachtSchwelle

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onPresetSelected(
                                        preset.nachtGrenzwertDb,
                                        if (auchTagSchwelleUebernehmen) preset.tagGrenzwertDb else null,
                                    )
                                    onDismissRequest()
                                },
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (istGewaehlt) {
                                    TechBluePrimary.copy(alpha = 0.12f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                }
                            ),
                            border = if (istGewaehlt) {
                                androidx.compose.foundation.BorderStroke(1.5.dp, TechBluePrimary)
                            } else null,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (istGewaehlt) TechBluePrimary else MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    Icon(
                                        imageVector = preset.icon,
                                        contentDescription = null,
                                        tint = if (istGewaehlt) MaterialTheme.colorScheme.onPrimary else TechBluePrimary,
                                        modifier = Modifier
                                            .padding(8.dp)
                                            .size(24.dp),
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = preset.titel,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            text = preset.normHinweis,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }

                                    Text(
                                        text = preset.untertitel,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )

                                    Spacer(modifier = Modifier.height(4.dp))

                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = "Nacht: ${preset.nachtGrenzwertDb.toInt()} dB(A)",
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                            color = TechBluePrimary,
                                        )
                                        Text(
                                            text = "Tag: ${preset.tagGrenzwertDb.toInt()} dB(A)",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }

                                if (istGewaehlt) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Aktiv",
                                        tint = TechBluePrimary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { auchTagSchwelleUebernehmen = !auchTagSchwelleUebernehmen }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = auchTagSchwelleUebernehmen,
                        onCheckedChange = { auchTagSchwelleUebernehmen = it },
                        colors = CheckboxDefaults.colors(checkedColor = TechBluePrimary),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Auch reguläre Tagesschwelle anpassen",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Schließen", color = TechBluePrimary)
            }
        },
    )
}
