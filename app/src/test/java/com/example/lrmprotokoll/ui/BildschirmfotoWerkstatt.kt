package com.example.lrmprotokoll.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.AppContainer
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.audio.AudioRecordingService
import com.example.lrmprotokoll.meter.FakeMeterTransport
import com.example.lrmprotokoll.ui.theme.LaermprotokollTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Nimmt Bildschirmfotos der Screens auf, ohne Emulator und ohne Gerät - Robolectric rendert im
 * GraphicsMode NATIVE echte Pixel, dieselbe Voraussetzung, die die vorhandenen Compose-Tests
 * dieses Verzeichnisses schon nutzen.
 *
 * Wozu: Gerätetest-Anleitungen, die aus dem Code abgeleitet sind, benennen Bedienelemente, die es
 * so nicht gibt oder die woanders liegen (Owner-Gerätetest zu PR #216, Schritte A4/A7/A8/B5). Wer
 * den Screen nicht sehen kann, schreibt Anleitungen ins Blaue.
 *
 * Die Bilder landen unter `app/build/screenshots/` und werden nicht eingecheckt.
 *
 * Die Höhe im `qualifiers` ist absichtlich unrealistisch groß: `captureToImage` nimmt nur den
 * sichtbaren Bereich auf, und ein langer Screen soll ohne Scrollen vollständig sichtbar sein.
 *
 * Zwei Fallen, beide gemessen, nicht vermutet:
 *
 * 1. `autoAdvance = false` ist Voraussetzung. Mehrere Screens enthalten unbestimmte Animationen
 *    (pulsierender Verbindungs-Badge, `CircularProgressIndicator`). Mit laufender Testuhr fordern
 *    sie endlos neue Frames an - derselbe Mechanismus wie in PR #209 und im Flakiness-Bericht
 *    Abschnitt 4.2. Die Uhr wird von Hand ein Stück vorgestellt, damit `LaunchedEffect`s
 *    durchlaufen, und dann angehalten fotografiert.
 * 2. `captureToImage()` ist trotzdem unbenutzbar: es wartet selbst auf einen ruhigen Zustand und
 *    lief auch mit angehaltener Uhr in die `ComposeTimeoutException`. Deshalb wird die `decorView`
 *    direkt auf eine Bitmap gezeichnet - das umgeht die Idle-Maschinerie vollständig.
 *    Preis: eigene Fenster (Dialoge, Bottom Sheets) liegen nicht in dieser View und erscheinen
 *    nicht auf dem Bild.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BildschirmfotoWerkstatt {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<LaermprotokollApp>()

    @Test
    fun einstellungenStartRegister() {
        nimmAuf("einstellungen_start_zugeklappt") {
            SettingsScreen(onBack = {}, onNavigateToMeter = {})
        }
    }

    @Test
    fun messgeraetScreenOhneGekoppeltesGeraet() {
        app.container.settingsManager.meterDeviceAddress = null
        nimmAuf("messgeraet_ohne_geraet") { MeterScreen(onBack = {}) }
    }

    @Test
    fun messgeraetScreenMitGekoppeltemGeraet() {
        app.container.settingsManager.meterDeviceAddress = "AA:BB:CC:DD:EE:FF"
        app.container.settingsManager.meterDeviceName = "PCE-323"
        nimmAuf("messgeraet_gekoppelt") { MeterScreen(onBack = {}) }
    }

    @Test
    fun einstellungenAbschnittSchwellenwerteAufgeklappt() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            LaermprotokollTheme { SettingsScreen(onBack = {}, onNavigateToMeter = {}) }
        }
        beruhige()
        composeRule.onNodeWithText(app.getString(R.string.settings_section_thresholds)).performClick()
        beruhige()
        speichere("einstellungen_schwellenwerte_aufgeklappt")
    }

    /**
     * Zeigt das Cockpit ohne laufende Messung. **Nicht** im verbundenen Zustand, obwohl genau der
     * für Gerätetest A5 zu PR #216 gebraucht würde - das geht hier nicht, und der Grund ist
     * gemessen: mit einem [FakeMeterTransport] erreicht der Transport zwar STREAMING, der
     * [com.example.lrmprotokoll.meter.ConnectionSupervisor] bleibt aber auf DISCONNECTED stehen.
     * Er veröffentlicht `supervisorOverride ?: fromTransport`, und der Override, den seine
     * Versuchsschleife in dieser Umgebung setzt, maskiert den weitergereichten Zustand dauerhaft.
     * `clearOverride()` und `setOverride()` sind privat, eine Nahtstelle dafür gibt es nicht.
     *
     * Gemessener Verlauf über 3 s: `supervisor=DISCONNECTED transport=STREAMING`, 30 von 30
     * Stichproben. Eine Testnahtstelle am Supervisor wäre eine Produktivcode-Änderung und damit
     * eine Owner-Entscheidung (AGENTS.md §8a), kein Alleingang dieser Werkstatt.
     */
    @Test
    fun cockpitOhneMessung() {
        app.setCustomContainer(AppContainer(app, FakeMeterTransport()))
        app.container.settingsManager.meterDeviceAddress = "AA:BB:CC:DD:EE:FF"
        app.container.settingsManager.meterDeviceName = "PCE-323"
        AudioRecordingService.testSetzeLaeuft(false)
        nimmAuf("cockpit_ohne_messung") { LiveCockpitCard() }
        app.resetContainer()
    }

    private fun beruhige() {
        repeat(20) {
            composeRule.mainClock.advanceTimeBy(100)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun nimmAuf(
        name: String,
        inhalt: @Composable () -> Unit,
    ) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { LaermprotokollTheme { inhalt() } }
        beruhige()
        speichere(name)
    }

    private fun speichere(name: String) {
        val view = composeRule.activity.window.decorView
        val bild =
            Bitmap.createBitmap(
                view.width.coerceAtLeast(1),
                view.height.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
        view.draw(Canvas(bild))
        val datei = File("build/screenshots/$name.png")
        datei.parentFile?.mkdirs()
        datei.outputStream().use { bild.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("Bildschirmfoto: ${datei.absolutePath} (${bild.width}x${bild.height})")
    }
}
