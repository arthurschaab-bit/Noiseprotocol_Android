package com.example.lrmprotokoll.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.AppContainer
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.audio.AudioRecordingService
import com.example.lrmprotokoll.meter.FakeMeterTransport
import com.example.lrmprotokoll.meter.ble.BluetoothPermissions
import com.example.lrmprotokoll.ui.theme.LaermprotokollTheme
import org.junit.Assert.assertTrue
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
 * Die Bilder landen unter `app/build/screenshots/`, werden nicht eingecheckt und von der CI als
 * Artefakt `bildschirmfotos` hochgeladen - auch bei rotem Lauf, denn gerade dann braucht man sie.
 *
 * **Zwei Bildschirmgrößen, mit Absicht.** [BILDSCHIRM_ECHT] ist ein realistisches Telefon; nur
 * solche Bilder taugen für eine Aussage über Auffindbarkeit. [BILDSCHIRM_LANG] ist unrealistisch
 * hoch und dient allein dazu, einen langen Screen am Stück zu zeigen - was dort ohne Scrollen
 * sichtbar wirkt, ist es auf einem echten Telefon nicht (Review-Befund zu PR #221).
 *
 * Die Werkstatt hat damit bereits etwas bewirkt: sie hat belegt, dass "Messgerät koppeln" am Ende
 * der Sektion "Schwellenwerte & Audio" bei y=935 px auf einem 891 px hohen Bildschirm lag, also
 * unerreichbar ohne Scrollen. PR #223 hat daraufhin eine eigene Messgerät-Sektion angelegt; die
 * Messung steht jetzt bei y=548 px, und [messgeraetKoppelnIstOhneScrollenSichtbar] hält das fest.
 *
 * Jede Aufnahme prüft, dass der erwartete Screen tatsächlich im Bild ist - siehe [speichere].
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
@Config(sdk = [34], qualifiers = BildschirmfotoWerkstatt.BILDSCHIRM_LANG)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BildschirmfotoWerkstatt {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<LaermprotokollApp>()

    @Test
    fun einstellungenStartRegister() {
        nimmAuf("einstellungen_start_zugeklappt", R.string.settings_section_thresholds) {
            SettingsScreen(onBack = {}, onNavigateToMeter = {})
        }
    }

    /**
     * Der Messgerät-Screen **ohne** erteilte Bluetooth-Berechtigung. Absichtlich als eigener Fall:
     * so heißt das Bild, was es zeigt, statt dass die Berechtigungskarte unbemerkt in jedem Bild
     * dieses Screens steht (Review-Befund zu PR #221).
     */
    @Test
    fun messgeraetScreenOhneBluetoothBerechtigung() {
        app.container.settingsManager.meterDeviceAddress = null
        nimmAuf("messgeraet_ohne_bluetooth_berechtigung", R.string.permission_bluetooth_title) {
            MeterScreen(onBack = {})
        }
    }

    @Test
    fun messgeraetScreenOhneGekoppeltesGeraet() {
        erteileBluetoothBerechtigungen()
        app.container.settingsManager.meterDeviceAddress = null
        nimmAuf("messgeraet_ohne_geraet", R.string.meter_pair_new_title) { MeterScreen(onBack = {}) }
        composeRule.onNodeWithText(app.getString(R.string.permission_bluetooth_title)).assertDoesNotExist()
    }

    @Test
    fun messgeraetScreenMitGekoppeltemGeraet() {
        erteileBluetoothBerechtigungen()
        app.container.settingsManager.meterDeviceAddress = "AA:BB:CC:DD:EE:FF"
        app.container.settingsManager.meterDeviceName = "PCE-323"
        nimmAuf("messgeraet_gekoppelt", R.string.meter_action_unpair) { MeterScreen(onBack = {}) }
        composeRule.onNodeWithText(app.getString(R.string.permission_bluetooth_title)).assertDoesNotExist()
    }

    /** Die aufgeklappte Messgerät-Sektion am Stück - siehe [BILDSCHIRM_LANG] zur Einordnung. */
    @Test
    fun einstellungenAbschnittMessgeraetAufgeklappt() {
        zeigeEinstellungenMitAufgeklappterMessgeraetSektion()
        speichere("einstellungen_messgeraet_aufgeklappt", R.string.settings_open_meter)
    }

    /**
     * Der Screen auf einem realistischen Telefon - **und dieser Test hat sein Vorzeichen
     * gewechselt.**
     *
     * Ursprünglich sicherte er zu, dass "Messgerät koppeln" **nicht** ohne Scrollen sichtbar ist:
     * der Knopf lag am Ende der Sektion "Schwellenwerte & Audio", gemessen bei y=935 px auf einem
     * 891 px hohen Bildschirm. Das war der Befund zur Auffindbarkeit, der PR #223 ausgelöst hat.
     * Im KDoc stand damals: schlägt die Zusicherung eines Tages fehl, ist der Befund behoben und
     * dieser Test gehört angepasst.
     *
     * Genau das ist eingetreten. #223 hat den Knopf in eine eigene Sektion "Messgerät (PCE-323)"
     * gezogen; die Messung auf demselben Bildschirm ergibt jetzt **y=548 px** statt 935. Die
     * Zusicherung ist deshalb umgedreht statt entfernt - sie hält fest, dass die Verbesserung
     * besteht, und schlägt an, wenn jemand den Knopf wieder nach unten schiebt.
     *
     * Der Abstand wird weiterhin gemessen und ausgegeben, damit eine Gerätetest-Anleitung ihn
     * nennen kann statt ihn zu schätzen.
     */
    @Test
    @Config(qualifiers = BILDSCHIRM_ECHT)
    fun messgeraetKoppelnIstOhneScrollenSichtbar() {
        zeigeEinstellungenMitAufgeklappterMessgeraetSektion()

        val knopf = composeRule.onNodeWithTag("btn_open_meter")
        val hoeheDerAnzeige = composeRule.activity.window.decorView.height
        val obenOhneScrollen = knopf.fetchSemanticsNode().positionInRoot.y
        println(
            "Auffindbarkeit: \"Messgerät koppeln\" beginnt bei y=$obenOhneScrollen px, " +
                "sichtbarer Bereich ist $hoeheDerAnzeige px hoch (vor PR #223: y=935 px)",
        )
        knopf.assertIsDisplayed()
        assertTrue(
            "Der Knopf muss ohne Scrollen im Bild liegen, war y=$obenOhneScrollen bei $hoeheDerAnzeige px",
            obenOhneScrollen < hoeheDerAnzeige,
        )
        speichere("echt_einstellungen_messgeraet", R.string.settings_open_meter)
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
        nimmAuf("cockpit_ohne_messung", R.string.cockpit_ready_to_measure) { LiveCockpitCard() }
        app.resetContainer()
    }

    /**
     * Der Bericht-Reiter nach S-4. Braucht Messwerte, sonst zeigt er nur "In diesem Zeitraum wurde
     * nicht gemessen" - und genau die Messtagsliste ist der Punkt dieses Umbaus.
     */
    @Test
    @Config(qualifiers = BILDSCHIRM_ECHT)
    fun berichtReiterMitMesstagen() {
        val heute = java.time.LocalDate.of(2026, 9, 29)
        val zone = java.time.ZoneId.systemDefault()
        listOf(heute, heute.minusDays(1), heute.minusDays(4)).forEach { tag ->
            kotlinx.coroutines.runBlocking {
                val id = app.container.database.sessionDao().insert(
                    com.example.lrmprotokoll.data.SessionEntity(
                        startedAt = tag.atTime(10, 0).atZone(zone).toInstant().toEpochMilli(),
                        endedAt = tag.atTime(12, 30).atZone(zone).toInstant().toEpochMilli(),
                        deviceAddress = "AA:BB",
                        deviceName = "PCE-323",
                        weighting = "A",
                        timeWeighting = "FAST",
                    ),
                )
                app.container.database.measurementDao().insertAll(
                    (0 until 40).map { i ->
                        com.example.lrmprotokoll.data.MeasurementEntity(
                            sessionId = id,
                            timestamp = tag.atTime(10, 0).atZone(zone).toInstant().toEpochMilli() + i * 60_000L,
                            levelDb = 50.0 + i % 7,
                            weighting = "A",
                            timeWeighting = "FAST",
                            flags = 0,
                        )
                    },
                )
            }
        }
        nimmAuf("bericht_reiter_messtage", R.string.bericht_ausgabe_gesamt_titel) {
            BerichtScreen(onBack = {}, onOpenSettings = {}, heute = heute)
        }
    }

    private fun zeigeEinstellungenMitAufgeklappterMessgeraetSektion() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            LaermprotokollTheme { SettingsScreen(onBack = {}, onNavigateToMeter = {}) }
        }
        beruhige()
        // Kein performScrollTo() vor diesem Klick: gemessen, dass der Klick dann verschluckt wird
        // (Abschnitt bleibt zu, "Messgerät koppeln" fehlt im Semantik-Baum). Der Abschnittskopf
        // ist die zweite Karte und ohnehin ohne Scrollen sichtbar.
        composeRule.onNodeWithText(app.getString(R.string.settings_section_meter)).performClick()
        beruhige()
    }

    /** Erteilt genau die Berechtigungen, die [BluetoothPermissions] für diese Android-Version prüft. */
    private fun erteileBluetoothBerechtigungen() {
        shadowOf(app).grantPermissions(*BluetoothPermissions.requiredPermissions())
    }

    private fun beruhige() {
        repeat(20) {
            composeRule.mainClock.advanceTimeBy(100)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun nimmAuf(
        name: String,
        erwarteterText: Int,
        inhalt: @Composable () -> Unit,
    ) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { LaermprotokollTheme { inhalt() } }
        beruhige()
        speichere(name, erwarteterText)
    }

    /**
     * Speichert und **prüft**. Ohne diese Prüfungen belegt ein grüner Lauf nicht, dass der
     * erwartete Screen im Bild ist: eine ungemessene View ergäbe ein 1x1-PNG, und ein
     * fehlgeschlagenes `compress()` bliebe unbemerkt (Review-Befund zu PR #221). Geprüft wird
     * deshalb dreierlei - ein kennzeichnender Text ist sichtbar, die View hat eine plausible
     * Größe, und die Datei ist geschrieben.
     */
    private fun speichere(
        name: String,
        erwarteterText: Int,
    ) {
        composeRule.onNodeWithText(app.getString(erwarteterText)).assertIsDisplayed()

        val view = composeRule.activity.window.decorView
        assertTrue(
            "View nicht plausibel vermessen ($name): ${view.width}x${view.height} px",
            view.width >= MINDESTBREITE_PX && view.height >= MINDESTHOEHE_PX,
        )
        val bild = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bild))
        val datei = File("build/screenshots/$name.png")
        datei.parentFile?.mkdirs()
        val geschrieben = datei.outputStream().use { bild.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("PNG-Kompression fehlgeschlagen ($name)", geschrieben)
        assertTrue("PNG ist leer ($name)", datei.length() > 0)
        println("Bildschirmfoto: ${datei.absolutePath} (${bild.width}x${bild.height})")
    }

    companion object {
        /**
         * Realistisches Telefon, dieselbe Größe wie in den bestehenden Compose-Tests. Das
         * `de-rDE` ist nicht schmückend: ohne es läuft Robolectric auf `en_US` und die Bilder
         * zeigen englische Beschriftungen, die auf einem deutschen Telefon niemand sieht - für
         * eine Gerätetest-Anleitung wäre das genau der falsche Text.
         */
        const val BILDSCHIRM_ECHT = "de-rDE-w411dp-h891dp"

        /**
         * Unrealistisch hoch, damit ein langer Screen am Stück sichtbar ist. Keine Aussage über
         * Auffindbarkeit: was hier ohne Scrollen zu sehen ist, ist es am Telefon nicht.
         */
        const val BILDSCHIRM_LANG = "de-rDE-w411dp-h2400dp"

        /** Untergrenzen gegen ein 1x1-Bild - nicht gegen eine bestimmte Bildschirmgröße. */
        private const val MINDESTBREITE_PX = 400
        private const val MINDESTHOEHE_PX = 600
    }
}
