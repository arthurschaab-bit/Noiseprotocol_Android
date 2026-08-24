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
    recordWavAudio: Boolean = true,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val (statusColor, containerColor, text) = when {
        !recordWavAudio -> Triple(
            Color(0xFF475569),
            Color(0xFFF1F5F9),
            "Audio AUS (DSGVO)"
        )
        audioMonitoringActive -> Triple(
            Color(0xFF15803D),
            Color(0xFFDCFCE7), // Grün (Aktiv)
            stringResource(R.string.status_mic_on)
        )
        else -> Triple(
            Color(0xFF64748B),
            Color(0xFFF1F5F9), // Schiefergrau (Aus / Pausiert)
            stringResource(R.string.status_mic_off)
        )
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
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}
