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
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.lrmprotokoll.meter.ConnectionState
import com.example.lrmprotokoll.meter.label
import com.example.lrmprotokoll.ui.theme.AppStatusColors
import com.example.lrmprotokoll.ui.theme.onStatusContainer
import com.example.lrmprotokoll.ui.theme.statusColors
import com.example.lrmprotokoll.ui.theme.statusContainer

/**
 * Status-Badge fuer die obere rechte Bildschirmecke. Der Text nennt den PCE-Zustand bewusst
 * explizit (nicht nur den Geraetenamen), damit auf dem Startbildschirm auf einen Blick erkennbar
 * ist, ob das Messgeraet tatsaechlich streamt.
 */
@Composable
fun BluetoothStatusBadge(
    state: ConnectionState,
    deviceName: String? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme.statusColors
    val statusColor = colors.fuerVerbindungszustand(state)
    val containerColor = MaterialTheme.colorScheme.statusContainer(statusColor)
    val textColor = MaterialTheme.colorScheme.onStatusContainer(statusColor)

    val isAnimating = state == ConnectionState.SCANNING ||
        state == ConnectionState.CONNECTING ||
        state == ConnectionState.DISCOVERING ||
        state == ConnectionState.SUBSCRIBING ||
        state == ConnectionState.RECONNECTING

    val infiniteTransition = if (isAnimating) rememberInfiniteTransition(label = "badgePulse") else null
    val pulseAlpha = infiniteTransition?.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        // F-21: Das Badge ist die primaere Bedienung fuer die Messgeraet-Verbindung, bestand
        // aber nur aus labelSmall-Text mit 5 dp Polsterung. minimumInteractiveComponentSize()
        // hebt die Trefferflaeche auf 48 dp, ohne die sichtbare Flaeche zu veraendern.
        modifier = modifier
            .then(
                if (onClick != null) {
                    Modifier
                        .minimumInteractiveComponentSize()
                        .clickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
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
                    .then(
                        if (pulseAlpha != null) {
                            Modifier.graphicsLayer { alpha = pulseAlpha.value }
                        } else {
                            Modifier
                        }
                    )
            )
            Spacer(modifier = Modifier.width(6.dp))

            val name = deviceName?.takeIf { it.isNotBlank() } ?: "PCE-323"
            // F-36: Die drei Sonderfaelle sind ersatzlos entfallen. [ConnectionState.label] ist
            // laut eigenem KDoc "an einer Stelle gepflegt" und unterscheidet IDLE
            // ("Nicht verbunden") von DISCONNECTED ("Getrennt") laengst - dieser Badge hat die
            // Unterscheidung wieder eingeebnet und dabei zusaetzlich "Fehler" statt
            // "Fehlgeschlagen" gesagt, also anders als Notification und Messgeraet-Screen.
            val displayText = "$name: ${state.label()}"

            Text(
                text = displayText,
                style = MaterialTheme.typography.labelSmall,
                color = textColor,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Die Statusfarbe je Verbindungszustand (F-36).
 *
 * Als reine Funktion neben dem Composable, damit die eine Zusicherung, um die es hier geht, ohne
 * Compose pruefbar ist: [ConnectionState.IDLE] und [ConnectionState.DISCONNECTED] duerfen nicht
 * dieselbe Farbe tragen. Bis zum 29.09.2026 taten sie das, und der Owner konnte am echten Geraet
 * die Schritte A1, A5 und B3 des Testplans zu PR #216 nicht beantworten - nicht wegen eines
 * Fehlers in der Verbindungslogik, sondern weil die Anzeige den Zustand nicht hergab.
 *
 * Die Zuordnung folgt der Frage "muss ich eingreifen?":
 * - grau: nichts laeuft (IDLE)
 * - blau/"connecting": es wird gerade aufgebaut
 * - gruen: es streamt
 * - gelb: etwas stimmt nicht, die Ueberwachung arbeitet daran (DEGRADED, RECONNECTING,
 *   DISCONNECTED)
 * - rot: aufgegeben, bis jemand eingreift (FAILED)
 */
fun AppStatusColors.fuerVerbindungszustand(zustand: ConnectionState): Color =
    when (zustand) {
        ConnectionState.STREAMING -> connected
        ConnectionState.SCANNING,
        ConnectionState.CONNECTING,
        ConnectionState.DISCOVERING,
        ConnectionState.SUBSCRIBING,
        -> connecting
        ConnectionState.RECONNECTING,
        ConnectionState.DEGRADED,
        ConnectionState.DISCONNECTED,
        -> warning
        ConnectionState.FAILED -> error
        ConnectionState.IDLE -> idle
    }
