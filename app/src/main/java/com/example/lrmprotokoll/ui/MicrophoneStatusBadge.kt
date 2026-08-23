package com.example.lrmprotokoll.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.lrmprotokoll.R

/**
 * Status-Badge für die TopAppBar, das anzeigt, ob die Mikrofonaufnahme bzw. -überwachung
 * aktiv oder pausiert/aus ist.
 */
@Composable
fun MicrophoneStatusBadge(
    audioMonitoringActive: Boolean,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val (statusColor, containerColor) = if (audioMonitoringActive) {
        Color(0xFF15803D) to Color(0xFFDCFCE7) // Grün (Aktiv)
    } else {
        Color(0xFF64748B) to Color(0xFFF1F5F9) // Schiefergrau (Aus / Pausiert)
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        modifier = modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Text(
                text = stringResource(if (audioMonitoringActive) R.string.status_mic_on else R.string.status_mic_off),
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
