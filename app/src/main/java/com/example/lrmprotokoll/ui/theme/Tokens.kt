package com.example.lrmprotokoll.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

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

/** Ein dezenter, deckender Statushintergrund auf der Oberflaeche des aktiven Schemas. */
fun ColorScheme.statusContainer(status: Color): Color =
    status.copy(alpha = 0.14f).compositeOver(surface)

/**
 * Die Schriftfarbe fuer Text auf [statusContainer].
 *
 * Im Dunkelmodus traegt die Statusfarbe selbst: die getoente Flaeche liegt unter der hellen
 * Statusfarbe, gemessen 4,59:1 bis 5,73:1. Im Hellmodus nicht — dort ist die Oberflaeche bereits
 * reines Weiss, die Toenung macht den Container also *dunkler* und schiebt ihn auf die Schrift zu.
 * Nach PR #227 lagen dort alle fuenf Paarungen zwischen 3,84:1 und 4,27:1 und damit unter WCAG AA
 * (4,5:1 fuer normalen Text; die Badges setzen labelSmall). Vor #227 hielten sieben von acht
 * handgesetzten Paarungen die Grenze — es ist ein Rueckschritt, kein Altbestand.
 *
 * Heller als Weiss geht der Container nicht, also muss die Schrift nachgeben: 15 % Schwarzanteil
 * heben die schlechteste Paarung auf 4,98:1, ohne den Farbton sichtbar zu verschieben.
 * `StatusFarbenKontrastTest` rechnet beide Modi nach und faellt, sobald eine Paarung reisst.
 */
fun ColorScheme.onStatusContainer(status: Color): Color =
    if (background == BackgroundDark) status else Color.Black.copy(alpha = 0.15f).compositeOver(status)
