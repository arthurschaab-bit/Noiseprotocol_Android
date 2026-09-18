package com.example.lrmprotokoll.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.example.lrmprotokoll.report.ReportArea
import com.example.lrmprotokoll.report.areaSelectionError

/** Feste Gebietsauswahl gemäß Owner-Entscheidung 18.09.2026. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReportAreaSelection(value: String, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = ReportArea.fromCode(value)
    val error = areaSelectionError(value)
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
    ) {
        OutlinedTextField(
            value = selected?.let { "${it.name} – ${it.label}" } ?: value,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("Gebietseinstufung") },
            placeholder = { Text("Gebiet auswählen") },
            isError = value.isNotBlank() && error != null,
            supportingText = {
                Text(error ?: "Die Auswahl bestätigt keine behördliche Einstufung. Ungeprüfte Gebietstypen sind nicht auswählbar.")
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor()
                .testTag("input_report_gebietseinstufung").fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ReportArea.entries.forEach { area ->
                DropdownMenuItem(
                    text = {
                        Text("${area.name} – ${area.label}" + if (area.hasVerifiedLimits) "" else " (Zuordnung ungeprüft)")
                    },
                    enabled = area.hasVerifiedLimits,
                    onClick = { onSelect(area.name); expanded = false },
                    modifier = Modifier.testTag("report_area_${area.name}"),
                )
            }
        }
    }
}
