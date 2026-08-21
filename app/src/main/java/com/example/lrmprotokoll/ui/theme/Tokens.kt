package com.example.lrmprotokoll.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * Semantische Statusfarben für die Lärmprotokoll-App (Messwerkzeug-Design).
 * Vermeidet hartcodierte Color(0xFF...)-Literale in Composables.
 */
data class AppStatusColors(
    val connected: Color,
    val connecting: Color,
    val warning: Color,
    val error: Color,
    val idle: Color,
    val livePulse: Color,
    val outageBand: Color,
    val thresholdLine: Color,
)

val LightStatusColors = AppStatusColors(
    connected = StatusConnectedLight,
    connecting = StatusConnectingLight,
    warning = StatusWarningLight,
    error = StatusErrorLight,
    idle = StatusIdleLight,
    livePulse = StatusConnectedLight,
    outageBand = OutageBandLight,
    thresholdLine = StatusErrorLight,
)

val DarkStatusColors = AppStatusColors(
    connected = StatusConnectedDark,
    connecting = StatusConnectingDark,
    warning = StatusWarningDark,
    error = StatusErrorDark,
    idle = StatusIdleDark,
    livePulse = StatusConnectedDark,
    outageBand = OutageBandDark,
    thresholdLine = StatusErrorDark,
)

val ColorScheme.statusColors: AppStatusColors
    @Composable
    @ReadOnlyComposable
    get() = if (this.background == BackgroundDark) DarkStatusColors else LightStatusColors
