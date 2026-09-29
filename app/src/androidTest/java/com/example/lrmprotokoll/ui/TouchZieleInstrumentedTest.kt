package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.meter.ConnectionState
import com.example.lrmprotokoll.ui.theme.LaermprotokollTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Misst die Trefferflaechen der beiden Status-Badges gegen 48 dp (Befund F-21).
 *
 * Der Audit nennt sie "die **primaere** Bedienung fuer Bluetooth-Verbindung und
 * WAV-Aufzeichnung", die aber "nur aus einem `labelSmall`-Text mit 5 dp vertikalem Padding"
 * bestand. Die vorhandenen Badge-Tests pruefen Sichtbarkeit und Klick und blieben davon
 * unberuehrt - eine zu kleine, aber korrekt reagierende Flaeche faellt ihnen nicht auf. Deshalb
 * diese eigene Messung.
 *
 * **Warum `getUnclippedBoundsInRoot`:** `minimumInteractiveComponentSize()` vergroessert die
 * Trefferflaeche, nicht die gezeichnete Flaeche. Die geclippten Grenzen wuerden weiterhin die
 * kleinere sichtbare Groesse melden und die Messung damit am Ziel vorbeifuehren.
 *
 * **Warum nur die Badges und nicht die fuenf IconButtons:** die liegen in Listen und Sheets, die
 * erst nach Datenbank- und Berechtigungszustand entstehen. Sie bekamen in derselben Aenderung
 * `sizeIn(minWidth = 48.dp, minHeight = 48.dp)`, was ihre Groesse direkt festlegt statt sie
 * auszurechnen - dort ist der Code die Zusicherung. Bei den Badges ist er es nicht, weil die
 * Groesse aus Text, Polsterung und Mindestflaeche entsteht.
 */
@RunWith(AndroidJUnit4::class)
class TouchZieleInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Untergrenze nach den Material-Design- und Android-Bedienungshilfen-Vorgaben. */
    private val mindestTouchZiel = 48.dp

    private fun pruefeMindestflaeche(
        bezeichnung: String,
        knoten: SemanticsNodeInteraction,
    ) {
        knoten.assertHasClickAction()
        val grenzen = knoten.getUnclippedBoundsInRoot()
        val meldung =
            "$bezeichnung: breite=${grenzen.width} hoehe=${grenzen.height} " +
                "(verlangt mindestens $mindestTouchZiel in beiden Richtungen)"
        println(meldung)
        assertTrue(meldung, grenzen.width >= mindestTouchZiel && grenzen.height >= mindestTouchZiel)
    }

    @Test
    fun bluetoothBadgeErreichtDasMindestTouchZiel() {
        composeRule.setContent {
            LaermprotokollTheme {
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    BluetoothStatusBadge(
                        state = ConnectionState.DISCONNECTED,
                        deviceName = "PCE-323",
                        onClick = {},
                        modifier = Modifier.testTag("badge_bluetooth_status"),
                    )
                }
            }
        }
        pruefeMindestflaeche("Bluetooth-Badge", composeRule.onNodeWithTag("badge_bluetooth_status"))
    }

    @Test
    fun mikrofonBadgeErreichtDasMindestTouchZiel() {
        composeRule.setContent {
            LaermprotokollTheme {
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    MicrophoneStatusBadge(
                        audioMonitoringActive = false,
                        recordWavAudio = true,
                        modifier = Modifier.testTag("badge_microphone_status"),
                    )
                }
            }
        }
        pruefeMindestflaeche("Mikrofon-Badge", composeRule.onNodeWithTag("badge_microphone_status"))
    }
}
