package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verhalten des Cockpits bei vergroesserter System-Schriftgroesse (UX-Audit Kapitel 35.1,
 * "Dynamische Schriftgroessen" - bis hierher nur als *Needs verification* vermerkt, weil in der
 * Entwicklungsumgebung kein Geraet lief).
 *
 * **Warum ueberhaupt ein Test:** Das Cockpit setzt an mehreren Stellen `maxLines = 1` und feste
 * Hoehen (der Startknopf z.B. `height(56.dp)`). Bei 130 % oder 200 % Schriftgroesse - unter
 * Android eine gewoehnliche Bedienungshilfe-Einstellung, kein Sonderfall - kann daraus
 * abgeschnittener Text oder ein unbedienbares Bedienelement werden. Kein bisheriger Test faellt
 * darauf, weil alle mit Standard-Schriftgroesse laufen.
 *
 * **Wie skaliert wird:** ueber [LocalDensity] mit unveraendertem `density` und erhoehtem
 * `fontScale` - genau die Groesse, die Android beim Schieberegler "Schriftgroesse" veraendert.
 *
 * **Bewusst abgestufte Schaerfe.** Bei Standardschrift wird *kein Ueberlauf* verlangt: Das muss
 * heute gelten und ist damit eine echte Regressionsbremse. Bei 130 % und 200 % wird nur
 * verlangt, dass die Bedienelemente vorhanden, sichtbar und klickbar bleiben - also kein
 * Layout-Zusammenbruch. Ob dort zusaetzlich Text abgeschnitten wird, ist der noch offene Befund
 * F-21 des Audits (feste Hoehen, `widthIn(max = 84.dp)` an den Status-Badges). Das hier bereits
 * als Erwartung festzuschreiben wuerde entweder den Fehler als gewolltes Verhalten zementieren
 * oder die CI rot faerben, bevor er behoben ist. **Sobald F-21 umgesetzt ist, gehoeren die
 * Ueberlauf-Pruefungen auf alle drei Stufen ausgeweitet** - dafuer steht [laeuftTextUeber]
 * bereits bereit.
 */
@RunWith(AndroidJUnit4::class)
class SchriftskalierungInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        // Das Cockpit zieht sich den AppContainer selbst aus der Application.
        ApplicationProvider.getApplicationContext<LaermprotokollApp>()
    }

    /** Rendert das Cockpit mit dem angegebenen Schriftfaktor bei unveraenderter Pixeldichte. */
    private fun zeigeCockpitMitSchriftfaktor(faktor: Float) {
        composeRule.setContent {
            val basis = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = basis.density, fontScale = faktor),
            ) {
                LiveCockpitCard()
            }
        }
        composeRule.waitForIdle()
    }

    /**
     * `hasVisualOverflow` ist die einzige belastbare Auskunft darueber, ob Compose einen Text
     * tatsaechlich abgeschnitten hat - der Semantik-Baum enthaelt weiterhin den vollstaendigen
     * String, `onNodeWithText` faende ihn also auch dann noch.
     */
    private fun laeuftTextUeber(knoten: SemanticsNodeInteraction): Boolean {
        val ergebnisse = mutableListOf<TextLayoutResult>()
        knoten.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(ergebnisse) }
        assertTrue(
            "Kein TextLayoutResult erhalten - der Knoten ist offenbar kein Text",
            ergebnisse.isNotEmpty(),
        )
        return ergebnisse.first().hasVisualOverflow
    }

    @Test
    fun beiStandardschriftIstNichtsAbgeschnitten() {
        zeigeCockpitMitSchriftfaktor(1.0f)

        val startBeschriftung = composeRule.activity.getString(R.string.cockpit_start_measurement)
        val titel = composeRule.activity.getString(R.string.cockpit_title)

        assertFalse(
            "Die Beschriftung des Startknopfes darf bei Standardschrift nicht abgeschnitten sein",
            laeuftTextUeber(composeRule.onNodeWithText(startBeschriftung)),
        )
        assertFalse(
            "Der Cockpit-Titel darf bei Standardschrift nicht abgeschnitten sein",
            laeuftTextUeber(composeRule.onNodeWithText(titel)),
        )
    }

    @Test
    fun beiEinhundertdreissigProzentBleibtDerStartknopfBedienbar() {
        // 130 %: die haeufigste Abweichung vom Standard, unter Android mit zwei Tipps erreichbar.
        zeigeCockpitMitSchriftfaktor(1.3f)

        composeRule
            .onNodeWithTag(START_MEASUREMENT_BUTTON_TAG)
            .assertIsDisplayed()
            .assertHasClickAction()
        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.cockpit_title))
            .assertIsDisplayed()
    }

    @Test
    fun beiZweihundertProzentBleibtDerStartknopfBedienbar() {
        // 200 %: der groesste Wert, den Android in den Bedienungshilfen anbietet. Hier geht es
        // um den Layout-Zusammenbruch (Knopf aus dem Bildschirm gedrueckt, Hoehe 0, Absturz beim
        // Messen) - nicht um Ellipsen, siehe Klassen-KDoc.
        zeigeCockpitMitSchriftfaktor(2.0f)

        composeRule
            .onNodeWithTag(START_MEASUREMENT_BUTTON_TAG)
            .assertIsDisplayed()
            .assertHasClickAction()
        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.cockpit_title))
            .assertIsDisplayed()
    }

    @Test
    fun derSchriftfaktorKommtInDerKompositionUeberhauptAn() {
        // Absicherung gegen einen stillen Fehlschlag der Testanordnung selbst: Kaeme der
        // Schriftfaktor gar nicht an, blieben die Tests oben gruen und waeren wertlos.
        // Geprueft wird am Cockpit-TITEL, nicht am Startknopf - der hat eine feste Hoehe von
        // 56 dp und kann gar nicht mitwachsen (genau das ist Teil von Befund F-21).
        val titel = composeRule.activity.getString(R.string.cockpit_title)

        zeigeCockpitMitSchriftfaktor(1.0f)
        val hoeheNormal =
            composeRule
                .onNodeWithText(titel)
                .fetchSemanticsNode()
                .size.height

        zeigeCockpitMitSchriftfaktor(2.0f)
        val hoeheGross =
            composeRule
                .onNodeWithText(titel)
                .fetchSemanticsNode()
                .size.height

        assertTrue(
            "Bei doppelter Schriftgroesse muss der Cockpit-Titel hoeher sein als bei einfacher " +
                "(normal=$hoeheNormal, gross=$hoeheGross) - sonst kommt der Schriftfaktor in " +
                "dieser Testanordnung gar nicht an und alle uebrigen Tests dieser Klasse waeren wertlos",
            hoeheGross > hoeheNormal,
        )
    }
}
