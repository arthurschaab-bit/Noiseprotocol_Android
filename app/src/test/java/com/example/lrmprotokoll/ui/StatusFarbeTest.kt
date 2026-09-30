package com.example.lrmprotokoll.ui

import androidx.compose.ui.graphics.Color
import com.example.lrmprotokoll.meter.ConnectionState
import com.example.lrmprotokoll.ui.theme.AppStatusColors
import com.example.lrmprotokoll.ui.theme.DarkStatusColors
import com.example.lrmprotokoll.ui.theme.LightStatusColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * F-36: Der Status-Badge hat [ConnectionState.IDLE] und [ConnectionState.DISCONNECTED] in dieselbe
 * graue Farbe gelegt und die Texte der beiden Zustaende zusaetzlich eingeebnet. Am echten Geraet
 * war damit nicht unterscheidbar, ob nie eine Verbindung bestand oder ob eine bestehende
 * abgerissen war — genau die Frage, die Schritt B3 des Testplans zu PR #216 stellt.
 */
class StatusFarbeTest {
    private val schemata = mapOf("hell" to LightStatusColors, "dunkel" to DarkStatusColors)

    @Test
    fun idleUndDisconnectedSindUnterscheidbar() {
        schemata.forEach { (name, farben) ->
            assertNotEquals(
                "Im Schema '$name' tragen IDLE und DISCONNECTED dieselbe Farbe",
                farben.fuerVerbindungszustand(ConnectionState.IDLE),
                farben.fuerVerbindungszustand(ConnectionState.DISCONNECTED),
            )
        }
    }

    @Test
    fun disconnectedIstEineWarnungKeinRuhezustand() {
        schemata.forEach { (name, farben) ->
            assertEquals(
                "Im Schema '$name' ist DISCONNECTED nicht als Warnung eingefaerbt",
                farben.warning,
                farben.fuerVerbindungszustand(ConnectionState.DISCONNECTED),
            )
        }
    }

    @Test
    fun jederZustandTraegtDieFarbeSeinerHandlungsaufforderung() {
        val erwartet: Map<ConnectionState, (AppStatusColors) -> Color> =
            mapOf(
                ConnectionState.IDLE to { it.idle },
                ConnectionState.SCANNING to { it.connecting },
                ConnectionState.CONNECTING to { it.connecting },
                ConnectionState.DISCOVERING to { it.connecting },
                ConnectionState.SUBSCRIBING to { it.connecting },
                ConnectionState.STREAMING to { it.connected },
                ConnectionState.DEGRADED to { it.warning },
                ConnectionState.RECONNECTING to { it.warning },
                ConnectionState.DISCONNECTED to { it.warning },
                ConnectionState.FAILED to { it.error },
            )

        assertEquals(
            "Der Test deckt nicht mehr alle Zustaende ab",
            ConnectionState.entries.toSet(),
            erwartet.keys,
        )

        schemata.forEach { (name, farben) ->
            erwartet.forEach { (zustand, farbe) ->
                assertEquals(
                    "Schema '$name', Zustand $zustand",
                    farbe(farben),
                    farben.fuerVerbindungszustand(zustand),
                )
            }
        }
    }
}
