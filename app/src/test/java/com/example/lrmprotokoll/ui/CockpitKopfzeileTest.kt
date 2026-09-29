package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.ui.theme.LaermprotokollTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regressionstest zu Befund F-34: der Cockpit-Titel bekam keine Breite zugeteilt.
 *
 * **Warum dieser Test hier und nicht nur im Emulator liegt.** Der vorhandene
 * `SchriftskalierungInstrumentedTest` misst dasselbe, braucht aber ein Geraet. Der Befund selbst
 * ist eine reine Layoutaussage — die Titelspalte bekam `maxBreite=0px` —, und die laesst sich
 * unter Robolectric mit echtem Rendering genauso messen. Damit faellt eine Rueckkehr des Fehlers
 * schon im JVM-Gate auf, nicht erst im Emulator-Job.
 *
 * Die Breite `w411dp` entspricht dem Geraetebild, auf dem der Befund urspruenglich gemessen
 * wurde; `w320dp` ist das schmalste Bild, das die App laut `minSdk 29` noch bedienen muss.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CockpitKopfzeileTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun zeigeCockpit() {
        ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        composeRule.setContent {
            LaermprotokollTheme {
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    LiveCockpitCard()
                }
            }
        }
        composeRule.waitForIdle()
    }

    /**
     * Das [TextLayoutResult] eines Textknotens. `size.width` daraus ist die einzige belastbare
     * Auskunft ueber die tatsaechlich zugeteilte Breite — der Semantikbaum enthaelt den
     * vollstaendigen String auch dann noch, wenn davon nichts dargestellt wurde. Genau daran ist
     * F-34 lange unbemerkt geblieben.
     */
    private fun textLayout(knoten: SemanticsNodeInteraction): TextLayoutResult {
        val ergebnisse = mutableListOf<TextLayoutResult>()
        knoten.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(ergebnisse) }
        assertTrue("Kein TextLayoutResult erhalten - der Knoten ist offenbar kein Text", ergebnisse.isNotEmpty())
        return ergebnisse.first()
    }

    /**
     * **Warum nicht `didOverflowWidth`.** Es meldet hier `true`, waehrend jeder andere Messwert
     * das Gegenteil sagt: der Titel braucht 111,15 px und bekommt 112 px. Derselbe Widerspruch
     * ist am 25.09.2026 im Emulator aufgetreten und in `SchriftskalierungInstrumentedTest`
     * dokumentiert; ein Merkmal, das dem eigenen Messwert widerspricht, taugt nicht als
     * Zusicherung. Geprueft wird deshalb das, was F-34 ausmacht: die Titelspalte bekommt
     * mindestens die Breite, die der Text braucht — vorher waren es 0 px.
     */
    private fun pruefeTitelBekommtSeineBreite() {
        val titel = composeRule.activity.getString(R.string.cockpit_title)
        val layout = textLayout(composeRule.onNodeWithText(titel))
        val benoetigt = layout.multiParagraph.minIntrinsicWidth
        val diagnose =
            "Cockpit-Titel: breite=${layout.size.width}px benoetigt=${benoetigt}px " +
                "maxBreite=${layout.layoutInput.constraints.maxWidth}px zeilen=${layout.lineCount} " +
                "ueberlaufHoehe=${layout.didOverflowHeight}"
        println(diagnose)

        assertTrue("Der Titel bekam ueberhaupt keine Breite zugeteilt (F-34) - $diagnose", layout.size.width > 0)
        assertTrue(
            "Der Titel bekommt weniger Breite als er braucht (F-34) - $diagnose",
            layout.size.width >= benoetigt,
        )
        assertEquals("Der Titel darf bei Standardschrift nicht umbrechen - $diagnose", 1, layout.lineCount)
        assertFalse("Der Titel darf nicht in der Hoehe abgeschnitten sein - $diagnose", layout.didOverflowHeight)
        assertTrue(
            "Der Titel belegt mehr Breite als die Constraints erlauben - $diagnose",
            layout.size.width <= layout.layoutInput.constraints.maxWidth,
        )
    }

    @Test
    @Config(sdk = [34], qualifiers = "de-rDE-w411dp-h891dp")
    fun aufDemGemessenenGeraetebildBekommtDerTitelBreite() {
        zeigeCockpit()
        pruefeTitelBekommtSeineBreite()
    }

    @Test
    @Config(sdk = [34], qualifiers = "de-rDE-w320dp-h640dp")
    fun aufDemSchmalstenGeraetebildBekommtDerTitelBreite() {
        zeigeCockpit()
        pruefeTitelBekommtSeineBreite()
    }
}
