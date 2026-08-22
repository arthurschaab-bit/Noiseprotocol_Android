package com.example.lrmprotokoll.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.lrmprotokoll.ui.theme.statusColors

enum class StatusPillType {
    CONNECTED,
    CONNECTING,
    WARNING,
    ERROR,
    IDLE,
    CALIBRATED,
    NEUTRAL,
    ACCENT
}

/**
 * Factual Status-Pille für schnelle 1-Sekunden-Erfassbarkeit (z.B. "Kalibriert", "PCE-323 Verbunden", "Leq 54.2 dB").
 */
@Composable
fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    type: StatusPillType = StatusPillType.NEUTRAL
) {
    val statusColors = MaterialTheme.colorScheme.statusColors

    val (bgColor, contentColor, borderColor) = when (type) {
        StatusPillType.CONNECTED -> Triple(
            statusColors.connected.copy(alpha = 0.15f),
            statusColors.connected,
            statusColors.connected.copy(alpha = 0.35f)
        )
        StatusPillType.CONNECTING -> Triple(
            statusColors.connecting.copy(alpha = 0.15f),
            statusColors.connecting,
            statusColors.connecting.copy(alpha = 0.35f)
        )
        StatusPillType.WARNING -> Triple(
            statusColors.warning.copy(alpha = 0.15f),
            statusColors.warning,
            statusColors.warning.copy(alpha = 0.35f)
        )
        StatusPillType.ERROR -> Triple(
            statusColors.error.copy(alpha = 0.15f),
            statusColors.error,
            statusColors.error.copy(alpha = 0.35f)
        )
        StatusPillType.IDLE -> Triple(
            statusColors.idle.copy(alpha = 0.12f),
            statusColors.idle,
            statusColors.idle.copy(alpha = 0.25f)
        )
        StatusPillType.CALIBRATED -> Triple(
            Color(0xFF388E3C).copy(alpha = 0.18f),
            Color(0xFF81C784),
            Color(0xFF388E3C).copy(alpha = 0.4f)
        )
        StatusPillType.ACCENT -> Triple(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
        )
        StatusPillType.NEUTRAL -> Triple(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
