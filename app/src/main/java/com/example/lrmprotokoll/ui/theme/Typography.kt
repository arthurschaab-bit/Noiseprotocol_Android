package com.example.lrmprotokoll.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlin.math.min

/**
 * Dynamische und auflösungsadaptive Typografie für Lärmprotokoll.
 *
 * Passt Schriftgrößen, Zeilenhöhen und Buchstabenspation dynamisch und universell an jede
 * Displayauflösung (kompakte Smartphones 320-360dp, Standard-Smartphones 360-400dp,
 * Großbildschirme 400-600dp sowie Tablets/Foldables >600dp) und die Systemeinstellungen
 * für Schriftgröße (fontScale) an.
 */
@Composable
fun provideAppTypography(): Typography {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidthDp = configuration.screenWidthDp

    return remember(screenWidthDp, density.fontScale) {
        // Skalierungsfaktor für kompakte Bildschirme (Basis: 392dp Standard-Viewport)
        val scaleFactor = when {
            screenWidthDp < 360 -> 0.88f // Kompakte Geräte (<360dp)
            screenWidthDp < 400 -> 0.94f // Standard-Smartphones (360-392dp)
            screenWidthDp < 600 -> 1.0f  // Größere moderne Smartphones (400-600dp)
            else -> 1.08f                 // Tablets / Foldables (>600dp)
        }

        // Bei extrem hoher System-Schriftgröße (fontScale > 1.15) begrenzen wir
        // Display-Großschriften, damit Messwerte nicht das Layout sprengen.
        val fontScaleCap = min(density.fontScale, 1.25f) / density.fontScale

        fun scaledSp(baseSp: Float, isDisplay: Boolean = false): TextUnit {
            val factor = if (isDisplay) scaleFactor * fontScaleCap else scaleFactor
            return (baseSp * factor).sp
        }

        Typography(
            displayLarge = TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Bold,
                fontSize = scaledSp(52f, isDisplay = true),
                lineHeight = scaledSp(58f, isDisplay = true),
                letterSpacing = (-0.5).sp
            ),
            displayMedium = TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Bold,
                fontSize = scaledSp(42f, isDisplay = true),
                lineHeight = scaledSp(48f, isDisplay = true),
                letterSpacing = (-0.25).sp
            ),
            displaySmall = TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Bold,
                fontSize = scaledSp(32f, isDisplay = true),
                lineHeight = scaledSp(38f, isDisplay = true),
            ),
            headlineLarge = TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.SemiBold,
                fontSize = scaledSp(28f),
                lineHeight = scaledSp(34f),
            ),
            headlineMedium = TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.SemiBold,
                fontSize = scaledSp(24f),
                lineHeight = scaledSp(30f),
            ),
            headlineSmall = TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.SemiBold,
                fontSize = scaledSp(20f),
                lineHeight = scaledSp(26f),
            ),
            titleLarge = TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Bold,
                fontSize = scaledSp(18f),
                lineHeight = scaledSp(24f),
            ),
            titleMedium = TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.SemiBold,
                fontSize = scaledSp(15f),
                lineHeight = scaledSp(20f),
            ),
            titleSmall = TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontSize = scaledSp(13f),
                lineHeight = scaledSp(18f),
            ),
            bodyLarge = TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Normal,
                fontSize = scaledSp(15f),
                lineHeight = scaledSp(21f),
            ),
            bodyMedium = TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Normal,
                fontSize = scaledSp(13f),
                lineHeight = scaledSp(18f),
            ),
            bodySmall = TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Normal,
                fontSize = scaledSp(11.5f),
                lineHeight = scaledSp(15f),
            ),
            labelLarge = TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.SemiBold,
                fontSize = scaledSp(13f),
                lineHeight = scaledSp(17f),
            ),
            labelMedium = TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontSize = scaledSp(11.5f),
                lineHeight = scaledSp(15f),
            ),
            labelSmall = TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontSize = scaledSp(10f),
                lineHeight = scaledSp(13f),
            )
        )
    }
}

/**
 * Berechnet eine dynamische, an die Displaybreite angepasste Schriftgröße.
 */
@Composable
fun responsiveSp(baseSp: Float, isDisplay: Boolean = false): TextUnit {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidthDp = configuration.screenWidthDp

    val scaleFactor = when {
        screenWidthDp < 360 -> 0.88f
        screenWidthDp < 400 -> 0.94f
        screenWidthDp < 600 -> 1.0f
        else -> 1.08f
    }
    val fontScaleCap = if (isDisplay) min(density.fontScale, 1.25f) / density.fontScale else 1f
    return (baseSp * scaleFactor * fontScaleCap).sp
}
