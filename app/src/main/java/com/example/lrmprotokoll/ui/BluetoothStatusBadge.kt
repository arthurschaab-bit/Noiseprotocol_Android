package com.example.lrmprotokoll.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.lrmprotokoll.meter.ConnectionState
import com.example.lrmprotokoll.meter.label

/**
 * Status-Badge für die obere rechte Bildschirmecke, das den aktuellen Bluetooth-/Messgerät-
 * Verbindungszustand kompakt anzeigt.
 */
@Composable
fun BluetoothStatusBadge(
    state: ConnectionState,
    deviceName: String? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val (statusColor, containerColor) = when (state) {
        ConnectionState.STREAMING -> Color(0xFF2E7D32) to Color(0xFFE8F5E9)
        ConnectionState.SCANNING,
        ConnectionState.CONNECTING,
        ConnectionState.DISCOVERING,
        ConnectionState.SUBSCRIBING -> Color(0xFF1565C0) to Color(0xFFE3F2FD)
        ConnectionState.RECONNECTING,
        ConnectionState.DEGRADED -> Color(0xFFE65100) to Color(0xFFFFF3E0)
        ConnectionState.FAILED -> Color(0xFFC62828) to Color(0xFFFFEBEE)
        ConnectionState.IDLE,
        ConnectionState.DISCONNECTED -> Color(0xFF616161) to Color(0xFFF5F5F5)
    }

    val isAnimating = state == ConnectionState.SCANNING ||
        state == ConnectionState.CONNECTING ||
        state == ConnectionState.DISCOVERING ||
        state == ConnectionState.SUBSCRIBING ||
        state == ConnectionState.RECONNECTING

    val infiniteTransition = rememberInfiniteTransition(label = "badgePulse")
    val pulseAlpha by if (isAnimating) {
        infiniteTransition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseAlpha"
        )
    } else {
        rememberInfiniteTransition(label = "static").animateFloat(
            initialValue = 1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1000)),
            label = "staticAlpha"
        )
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor)
                    .then(if (isAnimating) Modifier.alpha(pulseAlpha) else Modifier)
            )
            Spacer(modifier = Modifier.width(6.dp))

            val displayText = when {
                state == ConnectionState.STREAMING && !deviceName.isNullOrBlank() -> deviceName
                state == ConnectionState.STREAMING -> "BT: Verbunden"
                state == ConnectionState.IDLE -> "BT: Aus"
                else -> "BT: ${state.label()}"
            }

            Text(
                text = displayText,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
