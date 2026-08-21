package com.example.lrmprotokoll.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.lrmprotokoll.R

data class OnboardingPage(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val note: String? = null
)

@Composable
fun OnboardingScreen(
    onFinish: () -> Unit
) {
    var currentPage by remember { mutableIntStateOf(0) }

    val pages = listOf(
        OnboardingPage(
            title = "Willkommen beim Lärmprotokoll",
            description = "Dokumentieren Sie Lärmbelästigungen und akustische Ereignisse verlässlich mit automatischer Aufzeichnung und Pegelmessung.",
            icon = Icons.Default.Info,
            note = "Aufnahmen werden lokal auf Ihrem Gerät gespeichert und können bei Bedarf exportiert werden."
        ),
        OnboardingPage(
            title = "Zwei Betriebsarten",
            description = "1. Smartphone-Mikrofon: Schneller Einstieg, unkalibrierter dB-Vergleichswert.\n\n2. PCE-323 über Bluetooth BLE: Normgerechte, kalibrierte dBA-Messung für belastbare Gutachten.",
            icon = AppIcons.Sensors,
            note = "Wichtig: Smartphone-Mikrofone sind unkalibriert. Für amtliche Nachweise empfehlen wir das PCE-323."
        ),
        OnboardingPage(
            title = "Berechtigungen",
            description = "Die App fordert Berechtigungen gezielt an, wenn sie gebraucht werden:\n• Mikrofon: für Audioaufnahmen\n• Bluetooth: für die PCE-323 Kopplung\n• Benachrichtigungen: für den Hintergrunddienst",
            icon = Icons.Default.Notifications,
            note = "Keine unnötigen Berechtigungen im Voraus."
        ),
        OnboardingPage(
            title = "Akku-Optimierung",
            description = "Damit Messungen über viele Stunden im Hintergrund unterbrechungsfrei laufen, sollte die App von der Android Akku-Optimierung ausgenommen werden.",
            icon = Icons.Default.PlayArrow,
            note = "Sie können die Einstellung jederzeit in den App-Einstellungen anpassen."
        )
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header mit Überspringen
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (currentPage < pages.size - 1) {
                    TextButton(onClick = onFinish) {
                        Text(stringResource(R.string.onboarding_skip))
                    }
                } else {
                    Spacer(modifier = Modifier.height(48.dp))
                }
            }

            // Pager Content
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                val page = pages[currentPage]
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = page.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(52.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    Text(
                        text = page.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = page.description,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    page.note?.let { noteText ->
                        Spacer(modifier = Modifier.height(20.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = noteText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            // Footer mit Indikatoren und Weiter/Fertig
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Page Indicator Dots
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 24.dp)
                ) {
                    pages.indices.forEach { index ->
                        val isSelected = index == currentPage
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .size(if (isSelected) 10.dp else 8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant
                                )
                        )
                    }
                }

                Button(
                    onClick = {
                        if (currentPage < pages.size - 1) {
                            currentPage++
                        } else {
                            onFinish()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Text(
                        text = if (currentPage < pages.size - 1) stringResource(R.string.onboarding_next)
                        else stringResource(R.string.onboarding_finish),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}
