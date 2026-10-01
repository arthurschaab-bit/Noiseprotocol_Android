package com.example.lrmprotokoll.ui

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.AppContainer
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.audio.AudioRecordingService
import com.example.lrmprotokoll.data.SessionEntity
import com.example.lrmprotokoll.meter.BoundDevice
import com.example.lrmprotokoll.meter.ConnectionState
import com.example.lrmprotokoll.meter.FakeMeterTransport
import com.example.lrmprotokoll.meter.MeasurementRange
import com.example.lrmprotokoll.meter.TimeWeighting
import com.example.lrmprotokoll.meter.Weighting
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regressionsnetz fuer die App-Haelfte des Geraetetestplans zu PR #216 (S-3, F-02).
 * Testnamen tragen die Schritt-ID des Plans, damit ein roter Test unmittelbar auf eine
 * Checklistenzeile zeigt - siehe `docs/CI_ABDECKUNG_GERAETETEST.md`.
 *
 * **Warum diese Tests noetig sind, obwohl es schon drei Testklassen dazu gibt.**
 * [com.example.lrmprotokoll.messreihe.DashboardStatusTest] prueft `leitePegelAnzeigeAb` als reine
 * Funktion, [com.example.lrmprotokoll.meter.MeterAutoConnectTest] die Verbindungsentscheidung,
 * [com.example.lrmprotokoll.meter.ConnectionSupervisorTest] die Zustandsmaschine. Ungeprueft war
 * genau das Stueck dazwischen: **die Verdrahtung in die Anzeige.**
 *
 * Dort sass der Fehler, den der Owner am 27.09.2026 am echten PCE-323 gefunden hat: A5 und B4
 * zeigten `--.-`, obwohl das Geraet verbunden war, weil `isCalibrated` im Composable die
 * Bedingung "eine Messung laeuft" enthielt. Die reine Funktion war die ganze Zeit richtig - ein
 * Funktionstest haette den Fehler nie gefunden. Diese Klasse haette ihn gefunden.
 *
 * **Was hier NICHT geprueft werden kann und auch nie wird:** ob die angezeigte Zahl dem
 * Geraetedisplay entspricht (Plan-Schritt A5, +/-0,1 dB). Der Fake liefert die Zahl, die man ihm
 * vorgibt. Das bleibt Metrologie und damit Sache des Geraetetests.
 *
 * Zwei Fallen, beide in PR #238 gemessen und hier uebernommen:
 * 1. Ohne eingeschalteten Bluetooth-Adapter setzt der ConnectionSupervisor in seiner ersten
 *    Schleifeniteration `setOverride(DISCONNECTED)` und parkt - der Transport erreicht STREAMING,
 *    die Anzeige sieht es nie ([schalteBluetoothEin]).
 * 2. `FakeMeterTransport` setzt STREAMING in einer Coroutine auf eigenem Scope; `runBlocking`
 *    kehrt vorher zurueck. Deshalb wird auf den Zustand gewartet, nicht darauf gehofft.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "de-rDE-w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GeraetetestS3CockpitTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
    private val geraet = BoundDevice("AA:BB:CC:DD:EE:FF", "PCE-323")
    private lateinit var fake: FakeMeterTransport

    @Before
    fun setup() {
        fake = FakeMeterTransport()
        app.setCustomContainer(AppContainer(app, fake))
        schalteBluetoothEin()
        app.container.settingsManager.meterDeviceAddress = geraet.address
        app.container.settingsManager.meterDeviceName = geraet.name
        AudioRecordingService.testSetzeLaeuft(false)
    }

    @After
    fun cleanup() {
        AudioRecordingService.testSetzeLaeuft(false)
        app.container.connectionSupervisor.stop()
        val db = app.container.database
        val raeumer = Thread { db.clearAllTables() }
        raeumer.start()
        raeumer.join()
        app.resetContainer()
    }

    /**
     * Plan-Schritt **A5**, der Schritt, der am Geraet fehlschlug: Messgeraet verbunden,
     * **keine** Messung - die grosse Zahl zeigt den kalibrierten Wert, und darunter steht
     * "Live-Pegel · wird nicht aufgezeichnet".
     *
     * Gegenprobe: auf dem Stand vor `0359d01` stand hier `--.-`.
     */
    @Test
    fun a5_verbundenOhneMessungZeigtPegelUndKennzeichnetIhnAlsNichtAufgezeichnet() {
        verbindeUndSpeiseFrameEin(68.4)

        zeigeCockpit()

        composeRule.onNodeWithText("68.4").assertIsDisplayed()
        composeRule
            .onNodeWithText(app.getString(R.string.cockpit_live_level_not_recorded))
            .assertIsDisplayed()
    }

    /**
     * Plan-Schritt **B1**: sobald die Messung laeuft, verschwindet der Hinweis aus A5 - der Wert
     * wird ab jetzt aufgezeichnet, die Kennzeichnung waere falsch.
     */
    @Test
    fun b1_laufendeMessungLaesstDenLiveHinweisVerschwinden() {
        verbindeUndSpeiseFrameEin(68.4)
        oeffneSession()
        AudioRecordingService.testSetzeLaeuft(true)

        zeigeCockpit()

        composeRule.onNodeWithText("68.4").assertIsDisplayed()
        composeRule
            .onNodeWithText(app.getString(R.string.cockpit_live_level_not_recorded))
            .assertDoesNotExist()
    }

    /**
     * Plan-Schritt **B3**, der wichtigste Test dieses PRs: nach dem Ende der Messung laeuft der
     * Pegel **weiter** und der Hinweis ist **wieder da**. Genau das ist F-02 - "Messung beenden"
     * trennt die Verbindung nicht mehr mit.
     */
    @Test
    fun b3_nachMessungsendeLaeuftDerPegelWeiterUndDerHinweisIstWiederDa() {
        verbindeUndSpeiseFrameEin(68.4)
        oeffneSession()
        AudioRecordingService.testSetzeLaeuft(true)
        zeigeCockpit()
        composeRule
            .onNodeWithText(app.getString(R.string.cockpit_live_level_not_recorded))
            .assertDoesNotExist()

        composeRule.runOnIdle { AudioRecordingService.testSetzeLaeuft(false) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("68.4").assertIsDisplayed()
        composeRule
            .onNodeWithText(app.getString(R.string.cockpit_live_level_not_recorded))
            .assertIsDisplayed()
    }

    /**
     * Plan-Schritt **B3**, zweiter Anlauf - **der Test, der am Geraet gefehlt hat.**
     *
     * Der Test darueber simuliert das Messungsende mit `testSetzeLaeuft(false)` und prueft, dass
     * die Anzeige richtig darauf reagiert. Er geht damit **nicht durch den Dialog**, und genau
     * dort sass der Fehler: `LiveCockpitCard` rief im Bestaetigungsknopf
     * `container.connectionSupervisor.stop()` und trennte die Verbindung, die F-02 erhalten soll.
     *
     * Der Owner hat das am 30.09.2026 am PCE-323 gefunden: nach dem Messungsende war der Pegel
     * weg und die Ampel grau. Im Support-Bundle stand der Beweis - beide Messungsenden mit
     * `meterState=IDLE` in der Breadcrumb "Messgeraet-Verbindung bleibt bestehen (Automatik
     * aktiv)", also eine Erhaltungsmeldung ueber eine bereits getrennte Verbindung.
     *
     * Dieser Test faehrt deshalb die echte Bedienung: Knopf, Dialog, Bestaetigung.
     */
    @Test
    fun b3_messungUeberDenDialogBeendenTrenntDieVerbindungNicht() {
        verbindeUndSpeiseFrameEin(68.4)
        oeffneSession()
        AudioRecordingService.testSetzeLaeuft(true)
        zeigeCockpit()

        composeRule.onNodeWithTag(END_MEASUREMENT_BUTTON_TAG).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(END_MEASUREMENT_CONFIRM_DIALOG_TAG).assertExists()
        composeRule.onNodeWithTag(END_MEASUREMENT_CONFIRM_BUTTON_TAG).performClick()
        composeRule.waitForIdle()

        // Die Oberflaeche darf die Verbindung nicht angefasst haben. IDLE ist der Zustand, den
        // ConnectionSupervisor.stop() setzt - er waere der Fingerabdruck des alten Fehlers.
        assertNotEquals(
            ConnectionState.IDLE,
            app.container.connectionSupervisor.state.value,
        )
    }

    /**
     * Die Abgrenzung, ohne die die drei Tests oben zu viel behaupten wuerden: ohne Verbindung
     * gibt es keinen kalibrierten Wert und keinen Live-Hinweis. Der Supervisor wird hier
     * bewusst nicht gestartet.
     */
    @Test
    fun a5_ohneVerbindungGibtEsWederPegelNochLiveHinweis() {
        zeigeCockpit()

        composeRule.onNodeWithText("68.4").assertDoesNotExist()
        composeRule
            .onNodeWithText(app.getString(R.string.cockpit_live_level_not_recorded))
            .assertDoesNotExist()
    }

    private fun zeigeCockpit() {
        composeRule.setContent {
            LiveCockpitCard(modifier = Modifier.verticalScroll(rememberScrollState()))
        }
        composeRule.waitForIdle()
    }

    private fun oeffneSession() {
        runBlocking {
            app.container.database.sessionDao().insert(
                SessionEntity(
                    startedAt = System.currentTimeMillis(),
                    endedAt = null,
                    deviceAddress = geraet.address,
                    deviceName = geraet.name,
                    weighting = null,
                    timeWeighting = null,
                ),
            )
        }
    }

    /**
     * Reihenfolge ist tragend: `simulateStall(true)` VOR `connect()`, damit kein synthetischer
     * Frame der Emit-Schleife den eingespeisten Wert ueberschreibt, und das Warten auf STREAMING
     * nach `start()`, weil der Transport den Zustand asynchron setzt.
     */
    private fun verbindeUndSpeiseFrameEin(pegel: Double) {
        runBlocking { fake.simulateStall(true) }
        app.container.connectionSupervisor.start(geraet)
        runBlocking {
            fake.connect(geraet)
            withTimeout(TIMEOUT_MS) {
                app.container.connectionSupervisor.state
                    .first { it == ConnectionState.STREAMING }
            }
            fake.emitFrame(
                level = pegel,
                weighting = Weighting.A,
                timeWeighting = TimeWeighting.FAST,
                range = MeasurementRange.RANGE_30_130,
                modeAssumptionConfirmed = true,
            )
        }
    }

    /**
     * Voraussetzung dafuer, dass der ConnectionSupervisor ueberhaupt verbindet:
     * `BluetoothAdapterStateObserver` startet mit dem echten Adapterzustand, und der ist unter
     * Robolectric aus (gemessen in PR #238).
     */
    private fun schalteBluetoothEin() {
        app.getSystemService(BluetoothManager::class.java)?.adapter?.let { shadowOf(it).setEnabled(true) }
        app.sendBroadcast(
            Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
                .putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON),
        )
        shadowOf(Looper.getMainLooper()).idle()
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
