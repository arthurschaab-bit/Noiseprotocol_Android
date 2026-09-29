package com.example.lrmprotokoll.ui

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.audio.AudioRecordingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentierte UI-Tests für das Live-Cockpit ([LiveCockpitCard]) gemäß aktuellem UX-Design.
 *
 * Prüft die Anzeige des Inaktiv/Bereit-Status sowie des zentralen Buttons "Messung starten".
 *
 * **[statusWechseltLiveOhneDenScreenNeuZuOeffnen] trägt eine Diagnose mit** (Owner-Auftrag
 * 29.09.2026). Der Test ist der bekannte Flake (d) Ausprägung 2: er scheitert in etwa einem von
 * mehreren hundert Läufen mit `ComposeTimeoutException`, und zwar nur in der CI — der Emulator
 * fehlt in der Entwicklungsumgebung, eine Reproduktion vor Ort gibt es nicht. Ohne Messwerte in
 * der Fehlermeldung ist so ein Vorkommen nicht untersuchbar; genau dieses Vorgehen hat bei
 * Flake (a) drei Hypothesen widerlegt (`docs/CI_FLAKINESS_UNTERSUCHUNG_BERICHT.md` Abschnitt 4.4).
 * Was die Werte unterscheiden, steht bei [diagnose].
 *
 * **Grenze der Messreihe:** Grüne Läufe geben ihre Werte nur per `println` in das Logcat aus, und
 * das wird als Artefakt `emulator-diagnostics-api-34` nur bei **rotem** Job gesichert. Eine
 * Verteilung grüner Läufe wie in `app/build/flake_a_messwerte.tsv` gibt es hier also nicht —
 * dafür müsste der Emulator-Workflow zusätzlich `additional_test_output` einsammeln. Das ist
 * bewusst nicht Teil dieser Änderung.
 */
@RunWith(AndroidJUnit4::class)
class ServiceControlInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: LaermprotokollApp

    /**
     * Gegenprobe zur Diagnose: derselbe Mechanismus wie beim Produktivfluss
     * (`MutableStateFlow` -> `collectAsState` -> Rekomposition), aber eine eigene Instanz. Kommt
     * dieser Wert an und der andere nicht, liegt es nicht an Compose.
     */
    private val sondenFluss = MutableStateFlow(false)

    /** Aus der Komposition geschrieben - siehe [diagnose]. */
    @Volatile
    private var dienstAktivGesehen = false

    @Volatile
    private var sondeGesehen = false

    @Volatile
    private var rekompositionen = 0

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        sondenFluss.value = false
        dienstAktivGesehen = false
        sondeGesehen = false
        rekompositionen = 0
        stoppeServiceUndWarteAufIdle()
    }

    @After
    fun tearDown() {
        stoppeServiceUndWarteAufIdle()
    }

    private fun stoppeServiceUndWarteAufIdle() {
        app.stopService(Intent(app, AudioRecordingService::class.java))
        runBlocking {
            withTimeout(5_000L) {
                AudioRecordingService.laeuft.first { !it }
            }
        }
        assertFalse(
            "Der Idle-Test darf nur gegen einen tatsächlich gestoppten AudioRecordingService laufen",
            AudioRecordingService.laeuft.value,
        )
    }

    @Test
    fun liveCockpitCardZeigtInaktivenZustandUndStartButtonAktiv() {
        composeRule.setContent {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                LiveCockpitCard()
            }
        }
        val readyText = composeRule.activity.getString(com.example.lrmprotokoll.R.string.cockpit_ready_to_measure)

        composeRule.onNodeWithText(readyText).assertIsDisplayed()

        // In der realen Startseite liegt die Karte in einem scrollbaren Screen. Der Standalone-Test
        // bildet das nach und prüft, dass der zentrale Start-Button tatsächlich erreichbar ist.
        composeRule.onNodeWithTag(START_MEASUREMENT_BUTTON_TAG)
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
    }

    /**
     * Echtes Geraete-Pendant zu [ServiceControlComposeTest] (Robolectric, app/src/test) - Teil
     * der Bestandsaufnahme nach dem Datumsbereich-Dialog-Bug (Owner-Auftrag 15.09.2026).
     * Regressionstest fuer M7c Aufgabe 1: das Home-Dashboard zeigte den Monitoring-Status bisher
     * nur als einmaligen Snapshot - ein Servicestart/-stopp waehrend der Screen offen war, blieb
     * unsichtbar. Setzt den StateFlow direkt (wie das Robolectric-Original), statt den echten
     * Service zu starten, der Mikrofon-Hardware braeuchte.
     */
    @Test
    fun statusWechseltLiveOhneDenScreenNeuZuOeffnen() {
        composeRule.setContent {
            // Diagnose zu Flake (d) Auspraegung 2, siehe Klassen-KDoc. Die drei Zeilen schreiben
            // aus der Komposition in Testfelder. In Produktivcode waere das ein Fehler; hier ist
            // es der Zweck: nur so laesst sich im Moment der Zeitueberschreitung sagen, ob die
            // Komposition ueberhaupt noch lief und was sie dabei gesehen hat. Sie aendern weder
            // Layout noch Semantikbaum - ein zusaetzlicher Knoten koennte den Fehlschlag
            // verschieben, den er messen soll.
            rekompositionen++
            val dienstAusFluss by AudioRecordingService.laeuft.collectAsState()
            if (dienstAusFluss) dienstAktivGesehen = true
            val sonde by sondenFluss.collectAsState()
            if (sonde) sondeGesehen = true

            NoiseProtocolApp(
                onNavigateToPlayer = {},
                onNavigateToSettings = {},
                onNavigateToMeter = {},
                onNavigateToProtokoll = {},
                onNavigateToDiagnose = {},
                onNavigateToVideo = {},
            )
        }
        val startMeasurementText = composeRule.activity.getString(com.example.lrmprotokoll.R.string.cockpit_start_measurement)
        val measuringRunningText = composeRule.activity.getString(com.example.lrmprotokoll.R.string.cockpit_measuring_running)
        composeRule.onNodeWithText(startMeasurementText).assertExists()

        AudioRecordingService.testSetzeLaeuft(true)
        // Im selben Moment wie der Produktivfluss gesetzt, damit die Gegenprobe denselben
        // Zeitpunkt misst und nicht einen spaeteren.
        sondenFluss.value = true
        val beginn = System.currentTimeMillis()
        var pruefungen = 0
        try {
            val laufenderStatus =
                hasTestTag("cockpit_measurement_status") and hasText(measuringRunningText, substring = true)
            try {
                composeRule.waitUntil(10_000) {
                    pruefungen++
                    // Das Warn-Banner kann beim Statuswechsel vor dem Cockpit erscheinen. In der
                    // LazyColumn muss der Status deshalb nach jeder Umbau-Phase sichtbar bleiben.
                    composeRule.onNodeWithTag("home_lazy_column")
                        .performScrollToNode(hasTestTag("cockpit_measurement_status"))
                    composeRule.onAllNodes(laufenderStatus).fetchSemanticsNodes().isNotEmpty()
                }
            } catch (zeitueberschreitung: ComposeTimeoutException) {
                // runCatching: scheitert die Diagnose selbst, darf sie den eigentlichen
                // Fehlschlag nicht verdecken - dann bliebe von dem seltenen Vorkommen gar
                // nichts uebrig.
                val meldung =
                    runCatching { diagnose(beginn, pruefungen, measuringRunningText) }
                        .getOrElse { fehler ->
                            "Statuswechsel kam nicht an; Diagnose selbst gescheitert " +
                                "(${fehler.javaClass.simpleName}: ${fehler.message}) " +
                                "verstricheneMs=${System.currentTimeMillis() - beginn} pruefungen=$pruefungen"
                        }
                throw AssertionError(meldung, zeitueberschreitung)
            }
            println(
                "FLAKE-D gruen: verstricheneMs=${System.currentTimeMillis() - beginn} " +
                    "pruefungen=$pruefungen rekompositionen=$rekompositionen",
            )
            composeRule.onNode(laufenderStatus).assertIsDisplayed()
        } finally {
            AudioRecordingService.testSetzeLaeuft(false)
            sondenFluss.value = false
        }
    }

    /**
     * Sammelt im Moment der Zeitueberschreitung genau die Werte, die die offenen Erklaerungen
     * trennen. Die Ausgabe ist **eine Zeile** und steht als erste Zeile der Fehlermeldung, weil
     * `.github/scripts/testbericht.py` nur diese in die CI-Zusammenfassung uebernimmt.
     *
     * Was bisher feststeht (aus den Artefakten von Lauf 36382503990, `main`, Versuch 1): der
     * Fehlschlag ist eine `ComposeTimeoutException`, keine `AppNotIdleException` — die
     * Pruefschleife lief also durch, die App war erreichbar. Der Scroll auf
     * `cockpit_measurement_status` warf nie, der Knoten war also in jeder Pruefung da; nur sein
     * Text war 10 Sekunden lang der falsche. Der App-Prozess loggte in dieser Zeit nichts, und
     * jede Testmethode laeuft unter dem Orchestrator in einem eigenen Prozess — hinterlassener
     * Service-Zustand aus einer anderen Klasse scheidet damit aus.
     *
     * Offen ist genau eine Frage: stand `AudioRecordingService.laeuft` auf `true`, waehrend die
     * Komposition `false` zeigte? Die Felder trennen die Faelle:
     * - `laeuftWert=false` -> der Wert wurde zurueckgesetzt; dann ist die Ursache im Fluss, nicht
     *   in Compose, und der naechste Schritt ist die Suche nach dem Schreiber.
     * - `laeuftWert=true dienstGesehen=false sondeGesehen=true` -> die Komposition lief und ein
     *   **anderer** StateFlow kam an, dieser nicht. Die Ursache liegt dann an diesem Fluss oder
     *   seinem Collector, nicht an der Rekomposition allgemein.
     * - `laeuftWert=true dienstGesehen=false sondeGesehen=false` -> auch die Gegenprobe kam nicht
     *   an. Dann steht die Zustellung insgesamt, und das ist dieselbe ungeklaerte Signatur wie
     *   bei Flake (a) (Bericht Abschnitt 4.4), dort unter Robolectric.
     * - `dienstGesehen=true` bei falschem `statusText` -> der Wert kam an, nur der Knoten im
     *   Cockpit fuehrt ihn nicht nach. Dann ist es ein Compose-Problem in `LiveCockpitCard`.
     *
     * `rekompositionen` sagt unabhaengig davon, ob die Wurzel ueberhaupt noch neu zusammengesetzt
     * wurde.
     */
    private fun diagnose(
        beginn: Long,
        pruefungen: Int,
        erwarteterText: String,
    ): String {
        val knoten =
            composeRule
                .onAllNodesWithTag("cockpit_measurement_status")
                .fetchSemanticsNodes()
        val statusText =
            knoten
                .firstOrNull()
                ?.config
                ?.takeIf { it.contains(SemanticsProperties.Text) }
                ?.get(SemanticsProperties.Text)
                ?.joinToString(" ") { teil -> teil.text }
                ?: "<kein Text>"
        val zeile =
            "Statuswechsel kam nicht an. verstricheneMs=${System.currentTimeMillis() - beginn} " +
                "pruefungen=$pruefungen laeuftWert=${AudioRecordingService.laeuft.value} " +
                "dienstGesehen=$dienstAktivGesehen sondeGesehen=$sondeGesehen " +
                "rekompositionen=$rekompositionen statusKnoten=${knoten.size} " +
                "statusText=\"$statusText\" erwartet=\"$erwarteterText\""
        println("FLAKE-D rot: $zeile")
        return zeile
    }
}
