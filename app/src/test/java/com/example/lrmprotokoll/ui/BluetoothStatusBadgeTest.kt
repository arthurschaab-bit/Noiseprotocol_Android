package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.lrmprotokoll.meter.ConnectionState
import com.example.lrmprotokoll.meter.label
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BluetoothStatusBadgeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun zeigtGeraetenamenUndVerbundenBeiStreamingAn() {
        var clicked = false
        composeRule.setContent {
            BluetoothStatusBadge(
                state = ConnectionState.STREAMING,
                deviceName = "PCE-323",
                onClick = { clicked = true }
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("PCE-323: Verbunden").assertIsDisplayed().performClick()
        assertTrue("Badge-Klick-Callback muss ausgefuehrt werden", clicked)
    }

    @Test
    fun zeigtVerbindungsstatusBeiScanningAn() {
        composeRule.setContent {
            BluetoothStatusBadge(
                state = ConnectionState.SCANNING,
                deviceName = null
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("PCE-323: Suche…").assertIsDisplayed()
    }

    @Test
    fun zeigtNichtVerbundenBeiIdleAn() {
        composeRule.setContent {
            BluetoothStatusBadge(
                state = ConnectionState.IDLE,
                deviceName = null
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("PCE-323: Nicht verbunden").assertIsDisplayed()
    }

    /**
     * F-36: Der Badge hatte drei Sonderfaelle, die [ConnectionState.label] widersprachen — IDLE
     * und DISCONNECTED lasen sich beide "Nicht verbunden", FAILED las sich "Fehler" statt
     * "Fehlgeschlagen" wie in Notification und Messgeraet-Screen. Dieser Test haelt fest, dass
     * jeder Zustand genau seinen zentral gepflegten Text zeigt.
     */
    @Test
    fun jederZustandZeigtSeinenZentralGepflegtenText() {
        composeRule.setContent {
            Column {
                ConnectionState.entries.forEach { zustand ->
                    BluetoothStatusBadge(state = zustand, deviceName = "PCE-323")
                }
            }
        }
        composeRule.waitForIdle()

        ConnectionState.entries.forEach { zustand ->
            val erwartet = "PCE-323: ${zustand.label()}"
            val treffer = composeRule.onAllNodesWithText(erwartet).fetchSemanticsNodes()
            assertTrue("Kein Badge zeigt \"$erwartet\" (Zustand $zustand)", treffer.isNotEmpty())
        }
    }

    /**
     * F-36, Owner-Entscheidung 30.09.2026 (Variante d): in der TopAppBar des Startbildschirms
     * traegt der Badge nur Farbe und Punkt, weil der Text dort in **allen zehn** Zustaenden
     * abgeschnitten war (50 px Platz, 94-148 px noetig).
     *
     * Der Test sichert beide Haelften der Entscheidung: der Text ist wirklich weg, **und** er ist
     * nicht verloren - er steht vollstaendig in der contentDescription. Ohne die zweite Haelfte
     * waere der Badge fuer TalkBack ein namenloser Punkt, also eine Verschlechterung statt einer
     * Verbesserung.
     */
    @Test
    fun ohneTextTraegtDerBadgeDenVollenZustandInDerContentDescription() {
        composeRule.setContent {
            Column {
                ConnectionState.entries.forEach { zustand ->
                    BluetoothStatusBadge(
                        state = zustand,
                        deviceName = "PCE-323",
                        zeigeText = false,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        ConnectionState.entries.forEach { zustand ->
            val erwartet = "PCE-323: ${zustand.label()}"
            assertTrue(
                "Der Text \"$erwartet\" darf ohne zeigeText nicht sichtbar sein",
                composeRule.onAllNodesWithText(erwartet).fetchSemanticsNodes().isEmpty(),
            )
            assertTrue(
                "Ohne sichtbaren Text muss \"$erwartet\" in der contentDescription stehen",
                composeRule
                    .onAllNodesWithContentDescription(erwartet)
                    .fetchSemanticsNodes()
                    .isNotEmpty(),
            )
        }
    }

    @Test
    fun getrenntUndNichtVerbundenSindVerschiedeneTexte() {
        assertNotEquals(
            "IDLE und DISCONNECTED duerfen sich nicht gleich lesen",
            ConnectionState.IDLE.label(),
            ConnectionState.DISCONNECTED.label(),
        )
    }
}
