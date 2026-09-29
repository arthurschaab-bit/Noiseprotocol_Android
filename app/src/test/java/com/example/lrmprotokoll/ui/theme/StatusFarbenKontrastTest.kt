package com.example.lrmprotokoll.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Rechnet den Kontrast der Status-Badges nach, statt ihn zu behaupten.
 *
 * Anlass: PR #227 hat die handgesetzten Farbpaare durch [statusColors] und [statusContainer]
 * ersetzt. Im Dunkelmodus war das die Verbesserung, um die es ging. Im Hellmodus fielen dabei
 * **alle fuenf** Paarungen unter WCAG AA — gemessen 3,84:1 bis 4,27:1, wo vorher sieben von acht
 * handgesetzten Paarungen ueber 4,5:1 lagen. Der Fehler war unsichtbar, weil kein Test die
 * Farbwerte nachrechnete und eine Sichtpruefung im Emulator nur den Dunkelmodus betraf.
 *
 * Die Badges setzen `labelSmall` (11sp) — das ist normaler Text, also gilt 4,5:1, nicht 3:1.
 *
 * Die Rechnung folgt WCAG 2.1 (relative Leuchtdichte nach sRGB-Linearisierung). Sie braucht kein
 * Android: [Color] ist hier reine Arithmetik, der Test laeuft als reiner JVM-Test.
 */
class StatusFarbenKontrastTest {
    /** Untergrenze fuer normalen Text nach WCAG 2.1 AA. */
    private val aaNormalerText = 4.5

    private fun relativeLeuchtdichte(farbe: Color): Double {
        fun kanal(wert: Float): Double {
            val c = wert.toDouble()
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * kanal(farbe.red) + 0.7152 * kanal(farbe.green) + 0.0722 * kanal(farbe.blue)
    }

    private fun kontrast(
        vordergrund: Color,
        hintergrund: Color,
    ): Double {
        val a = relativeLeuchtdichte(vordergrund)
        val b = relativeLeuchtdichte(hintergrund)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    /**
     * Die Statusfarben beider Modi mit der Oberflaeche, auf der ihre Container liegen.
     * Die Werte stammen aus `Theme.kt` — dort setzt `LaermprotokollTheme` `surface` je Schema.
     */
    private fun paarungen(): List<Triple<String, Color, Color>> {
        val hell = LightStatusColors to SurfaceLight
        val dunkel = DarkStatusColors to SurfaceDark
        return listOf("hell" to hell, "dunkel" to dunkel).flatMap { (modus, schema) ->
            val (farben, oberflaeche) = schema
            listOf(
                "connected" to farben.connected,
                "connecting" to farben.connecting,
                "warning" to farben.warning,
                "error" to farben.error,
                "idle" to farben.idle,
            ).map { (name, status) -> Triple("$modus/$name", status, oberflaeche) }
        }
    }

    /**
     * Bildet [onStatusContainer] nach. Die Produktivfunktion haengt an einem `ColorScheme`, das
     * hier nicht gebaut werden soll — entscheidend ist dieselbe Regel: im Dunkelmodus traegt die
     * Statusfarbe selbst, im Hellmodus wird sie um 15 % abgedunkelt.
     */
    private fun schriftfarbe(
        status: Color,
        oberflaeche: Color,
    ): Color =
        if (oberflaeche == SurfaceDark) {
            status
        } else {
            Color.Black.copy(alpha = 0.15f).compositeOver(status)
        }

    private fun container(
        status: Color,
        oberflaeche: Color,
    ): Color = status.copy(alpha = 0.14f).compositeOver(oberflaeche)

    @Test
    fun jedeStatusPaarungHaeltWcagAaFuerNormalenText() {
        val gerissen = mutableListOf<String>()
        paarungen().forEach { (name, status, oberflaeche) ->
            val flaeche = container(status, oberflaeche)
            val schrift = schriftfarbe(status, oberflaeche)
            val wert = kontrast(schrift, flaeche)
            println("Kontrast $name: %.2f:1".format(wert))
            if (wert < aaNormalerText) gerissen += "$name = %.2f:1".format(wert)
        }
        assertTrue(
            "Diese Paarungen liegen unter WCAG AA ($aaNormalerText:1): $gerissen",
            gerissen.isEmpty(),
        )
    }

    /**
     * Haelt fest, was ohne die Abdunklung passiert. Faellt dieser Test, ist die Korrektur aus
     * [onStatusContainer] wirkungslos geworden — und der Zustand von PR #227 ist zurueck.
     */
    @Test
    fun ohneAbdunklungReisstDerHellmodusNachweislich() {
        val ohneKorrektur =
            listOf(
                "connected" to LightStatusColors.connected,
                "connecting" to LightStatusColors.connecting,
                "warning" to LightStatusColors.warning,
                "error" to LightStatusColors.error,
                "idle" to LightStatusColors.idle,
            ).map { (name, status) -> name to kontrast(status, container(status, SurfaceLight)) }
        val alleUnterAa = ohneKorrektur.all { (_, wert) -> wert < aaNormalerText }
        assertTrue(
            "Erwartet war, dass ohne Abdunklung jede Hell-Paarung unter AA liegt, gemessen: $ohneKorrektur",
            alleUnterAa,
        )
    }

    /** Der Dunkelmodus kam ohne Korrektur aus — das war der Gewinn von PR #227 und bleibt so. */
    @Test
    fun derDunkelmodusHaeltOhneAbdunklung() {
        listOf(
            "connected" to DarkStatusColors.connected,
            "connecting" to DarkStatusColors.connecting,
            "warning" to DarkStatusColors.warning,
            "error" to DarkStatusColors.error,
            "idle" to DarkStatusColors.idle,
        ).forEach { (name, status) ->
            val wert = kontrast(status, container(status, SurfaceDark))
            assertTrue("dunkel/$name liegt bei %.2f:1".format(wert), wert >= aaNormalerText)
        }
    }
}
