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
import androidx.compose.ui.res.stringResource
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.report.ReportArea

@Composable
private fun reportAreaLabel(area: ReportArea): String =
    stringResource(
        when (area) {
            ReportArea.WA -> R.string.report_area_wa
            ReportArea.WR -> R.string.report_area_wr
            ReportArea.MI -> R.string.report_area_mi
            ReportArea.GE -> R.string.report_area_ge
            ReportArea.GI -> R.string.report_area_gi
            ReportArea.WS -> R.string.report_area_ws
            ReportArea.WB -> R.string.report_area_wb
            ReportArea.MD -> R.string.report_area_md
            ReportArea.MDW -> R.string.report_area_mdw
            ReportArea.MU -> R.string.report_area_mu
            ReportArea.MK -> R.string.report_area_mk
        },
    )

/** Feste Gebietsauswahl gemäß Owner-Entscheidung 18.09.2026. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReportAreaSelection(value: String, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = ReportArea.fromCode(value)
    val error =
        when {
            value.isBlank() -> stringResource(R.string.report_precondition_error_area_missing)
            selected == null -> stringResource(R.string.report_precondition_error_area_unknown, value)
            !selected.hasVerifiedLimits ->
                stringResource(R.string.report_area_unverified_error, reportAreaLabel(selected), selected.name)
            else -> null
        }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
    ) {
        OutlinedTextField(
            value = selected?.let { stringResource(R.string.report_area_option, it.name, reportAreaLabel(it)) } ?: value,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(stringResource(R.string.report_area_selection_label)) },
            placeholder = { Text(stringResource(R.string.report_area_selection_placeholder)) },
            isError = value.isNotBlank() && error != null,
            supportingText = {
                Text(error ?: stringResource(R.string.report_area_selection_supporting_default))
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor()
                .testTag("input_report_gebietseinstufung").fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ReportArea.entries.forEach { area ->
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (area.hasVerifiedLimits) R.string.report_area_option else R.string.report_area_option_unverified,
                                area.name,
                                reportAreaLabel(area),
                            ),
                        )
                    },
                    enabled = area.hasVerifiedLimits,
                    onClick = { onSelect(area.name); expanded = false },
                    modifier = Modifier.testTag("report_area_${area.name}"),
                )
            }
        }
    }
}
