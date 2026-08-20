package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regressionstest zu M7c Aufgabe 4 (Bestandsaufnahme-Befund 4.2): MeterScreen stand vor der
 * Umstellung auf ein einziges LazyColumn in einer Column ohne verticalScroll, mit einem
 * verschachtelten LazyColumn nur fuer die Geraeteliste - derselbe Bug-Klasse wie der
 * SettingsScreen-Scroll-Bug (PR #27), hier aber vorbeugend behoben statt nach einem Feldbericht.
 *
 * Anders als der generische Spike (PR #29) laeuft dieser Test gegen die ECHTE, produktive
 * MeterScreen()-Funktion mit dem vollen AppContainer (ueber LaermprotokollApp, wie im
 * Manifest als android:name deklariert) - klaert die dort offen gelassene Frage, ob das unter
 * Robolectric genauso sauber funktioniert wie das triviale Beispiel. Ergebnis: ja, siehe unten.
 *
 * Feste, kleine Bildschirmgroesse (w320dp-h240dp) erzwingt, dass der feste Kopfbereich (Status,
 * Bonding-Warnung, Trenner, "Neues Gerät koppeln", Scan-Button) allein schon mehr Platz braucht
 * als der Viewport hoch ist - ohne echte Bluetooth-Geraete im Scan-Ergebnis noetig zu haben.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h240dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeterScreenComposeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun scanButtonAmEndeDesKopfbereichsIstPerScrollErreichbar() {
        // Erzwingt, dass LaermprotokollApp.onCreate() (und damit AppContainer) bereits gelaufen
        // ist, bevor MeterScreen darauf zugreift - unter Robolectric normalerweise automatisch
        // der Fall, hier zur Klarheit explizit angefordert.
        ApplicationProvider.getApplicationContext<LaermprotokollApp>()

        composeRule.setContent { MeterScreen(onBack = {}) }

        composeRule.onNodeWithTag(SCAN_BUTTON_TAG).performScrollTo().assertIsDisplayed()
    }
}
