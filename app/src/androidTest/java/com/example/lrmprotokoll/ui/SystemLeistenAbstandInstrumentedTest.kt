package com.example.lrmprotokoll.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.LaermprotokollApp
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Liegt Inhalt unter der Statusleiste? (UX-Audit Kapitel 35.1, "System-Insets".)
 *
 * **Warum das offen war:** `MainActivity` ruft `enableEdgeToEdge()`, das aeussere `Scaffold`
 * setzt `contentWindowInsets = WindowInsets(0, 0, 0, 0)` und gibt dem `NavHost` ausschliesslich
 * ein *unteres* Padding mit. Ob der obere Rand trotzdem frei bleibt, haengt allein daran, dass
 * die einzelnen `TopAppBar`s ihre Insets selbst anwenden - aus dem Quelltext ist das nicht
 * zuverlaessig ableitbar, es war deshalb nur als *Needs verification* vermerkt.
 *
 * **Was hier geprueft wird:** dass der Titel des Startbildschirms tatsaechlich unterhalb der
 * Statusleiste beginnt. Das ist die Stelle mit dem hoechsten Risiko, weil die TopAppBar dort
 * nicht im `Scaffold`-Slot sitzt, sondern als erstes Element einer `LazyColumn` gerendert wird.
 *
 * Gemessen wird in Fensterkoordinaten (`positionInWindow`), nicht relativ zur Compose-Wurzel -
 * die Wurzel kann selbst bereits verschoben sein und wuerde einen Ueberlapp verdecken.
 */
@RunWith(AndroidJUnit4::class)
class SystemLeistenAbstandInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        // Ohne das landet der Test nach dem Onboarding-Fix (F-14) auf der Einfuehrung statt auf
        // dem Startbildschirm - dieselbe Vorbereitung nutzt AppStartupSmokeInstrumentedTest.
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        app.container.settingsManager.onboardingCompleted = true
    }

    @Test
    fun derTitelDesStartbildschirmsBeginntUnterhalbDerStatusleiste() {
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onAllNodesWithTag("home_title").fetchSemanticsNodes().isNotEmpty()
        }

        val rohInsets = composeRule.activity.window.decorView.rootWindowInsets
        assumeTrue("Ohne rootWindowInsets laesst sich nichts messen", rohInsets != null)
        val insets = WindowInsetsCompat.toWindowInsetsCompat(rohInsets!!)
        val statusleisteOben = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
        val systemleistenOben = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top

        // Auf einem Geraetebild ohne Statusleiste gibt es nichts zu ueberlappen - dann ist der
        // Test gegenstandslos statt gruen-durch-Zufall.
        //
        // WICHTIG (Emulator-Lauf 25.09.2026): Genau hier hat sich der Test weggeskippt und damit
        // NICHTS geprueft. Wahrscheinlichste Ursache ist das Geraetebild der CI:
        // emulator-tests.yml verwendet `target: aosp_atd`. Ein Automated Test Device ist bewusst
        // abgespeckt und bringt keine SystemUI mit - ohne SystemUI gibt es keine Statusleiste und
        // folglich keinen Inset. Trifft das zu, laesst sich dieser Punkt aus Audit-Kapitel 35.1
        // auf diesem Geraetebild grundsaetzlich nicht pruefen; er braeuchte ein Bild mit SystemUI
        // (z. B. `google_apis`). Das ist eine CI-Entscheidung des Owners (AGENTS.md §8a), nicht
        // eine, die dieser Test eigenmaechtig treffen darf - deshalb ueberspringt er sich
        // weiterhin, sagt jetzt aber im Bericht, was er gemessen hat.
        assumeTrue(
            "Dieses Geraetebild hat keine Statusleiste (statusBars.top=$statusleisteOben, " +
                "systemBars.top=$systemleistenOben) - vermutlich ein aosp_atd-Bild ohne " +
                "SystemUI. Der Punkt aus Audit-Kapitel 35.1 bleibt damit ungeprueft.",
            statusleisteOben > 0,
        )

        val titelOben =
            composeRule
                .onNodeWithTag("home_title")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .positionInWindow.y

        assertTrue(
            "Der Titel des Startbildschirms beginnt bei y=$titelOben und liegt damit unter der " +
                "Statusleiste (Hoehe $statusleisteOben px) - die TopAppBar der LazyColumn wendet " +
                "ihre System-Insets offenbar nicht an (UX-Audit Kapitel 35.1)",
            titelOben >= statusleisteOben,
        )
    }
}
