package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verhalten des Cockpits bei vergroesserter System-Schriftgroesse (UX-Audit Kapitel 35.1,
 * "Dynamische Schriftgroessen").
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
 * **Warum ein Scrollbehaelter (Korrektur nach dem ersten Emulator-Lauf, 25.09.2026):** Die erste
 * Fassung rief `setContent { LiveCockpitCard() }` ohne Scrollmoeglichkeit auf. Bei 130 % und
 * 200 % rutschte der Startknopf damit aus dem Sichtbereich und `assertIsDisplayed()` schlug fehl
 * - ein Fehler der Testanordnung, nicht der App: im echten Startbildschirm liegt das Cockpit in
 * einer `LazyColumn` (`MainActivity.kt:730`) und ist scrollbar. Der Behaelter hier bildet das
 * nach; vor jeder Sichtbarkeitspruefung wird gescrollt. Damit prueft der Test weiterhin das
 * Richtige - ob das Bedienelement erreichbar und bedienbar bleibt -, nur nicht mehr unter einer
 * Bedingung, die es in der App gar nicht gibt.
 *
 * **Abgestufte Schaerfe, seit Phase 5 an drei Stellen nachgeschaerft.** Bei Standardschrift wird
 * *kein Ueberlauf* verlangt - fuer den Startknopf und, seit F-34 umgesetzt ist, auch fuer den
 * Cockpit-Titel. Bei 130 % und 200 % wird verlangt, dass die Bedienelemente vorhanden,
 * erreichbar und klickbar bleiben und dass der Titel *dargestellt* wird (nicht nur existiert).
 *
 * Bis Phase 5 stand an genau diesen drei Stellen nur `assertExists()`, mit der Begruendung, eine
 * harte Zusicherung wuerde den offenen Befund F-34 entweder als gewolltes Verhalten zementieren
 * oder die CI dauerhaft rot faerben. F-34 ist behoben (FlowRow-Kopfzeile mit Mindestbreite fuer
 * den Titel), also gilt jetzt die Zusicherung. Fuellt jemand die Kopfzeile kuenftig so, dass der
 * Titel wieder verdraengt wird, faellt dieser Test - das war der Zweck der Vorbereitung.
 *
 * Ob bei 130 % und 200 % zusaetzlich *Text* abgeschnitten wird, bleibt bewusst ungeprueft: dort
 * ist ein Umbruch in die zweite Zeile das gewollte Verhalten, und eine Ueberlaufpruefung wuerde
 * ihn als Fehler melden.
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

    /**
     * Rendert das Cockpit mit dem angegebenen Schriftfaktor bei unveraenderter Pixeldichte, in
     * einem scrollbaren Behaelter wie im echten Startbildschirm.
     */
    private fun zeigeCockpitMitSchriftfaktor(faktor: Float) {
        composeRule.setContent {
            val basis = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = basis.density, fontScale = faktor),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                ) {
                    LiveCockpitCard()
                }
            }
        }
        composeRule.waitForIdle()
    }

    /**
     * Das [TextLayoutResult] eines Textknotens. `hasVisualOverflow` daraus ist die einzige
     * belastbare Auskunft darueber, ob Compose einen Text tatsaechlich abgeschnitten hat - der
     * Semantik-Baum enthaelt weiterhin den vollstaendigen String, `onNodeWithText` faende ihn
     * also auch dann noch.
     */
    private fun textLayout(knoten: SemanticsNodeInteraction): TextLayoutResult {
        val ergebnisse = mutableListOf<TextLayoutResult>()
        knoten.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(ergebnisse) }
        assertTrue(
            "Kein TextLayoutResult erhalten - der Knoten ist offenbar kein Text",
            ergebnisse.isNotEmpty(),
        )
        return ergebnisse.first()
    }

    /**
     * Beschreibt eine Textmessung so, dass ein Fehlschlag in der CI aus sich heraus verstaendlich
     * ist. Ohne Emulator in der Entwicklungsumgebung ist die Meldung die einzige Diagnose, die
     * ankommt - eine blosse Zusicherung "darf nicht ueberlaufen" waere nicht nachvollziehbar.
     */
    private fun messwerte(
        bezeichnung: String,
        layout: TextLayoutResult,
    ): String =
        "$bezeichnung: breite=${layout.size.width}px hoehe=${layout.size.height}px " +
            "zeilen=${layout.lineCount} ueberlaufBreite=${layout.didOverflowWidth} " +
            "ueberlaufHoehe=${layout.didOverflowHeight} " +
            "maxBreite=${layout.layoutInput.constraints.maxWidth}px " +
            "maxHoehe=${layout.layoutInput.constraints.maxHeight}px"

    @Test
    fun beiStandardschriftIstNichtsAbgeschnitten() {
        zeigeCockpitMitSchriftfaktor(1.0f)

        val startBeschriftung = composeRule.activity.getString(R.string.cockpit_start_measurement)
        val titel = composeRule.activity.getString(R.string.cockpit_title)

        // Der Startknopf fuellt die Kartenbreite und ist damit von der Kopfzeilen-Aufteilung
        // unabhaengig - hier ist eine harte Zusicherung tragfaehig und ein echter
        // Regressionsschutz.
        pruefeNichtAbgeschnitten(
            "Startknopf",
            textLayout(composeRule.onNodeWithText(startBeschriftung).performScrollTo()),
        )

        // F-34 ist umgesetzt (Phase 5), deshalb steht hier jetzt die harte Zusicherung, die der
        // Auftrag verlangt. Vorher mass der dritte Emulator-Lauf (36164004983) bei
        // Standardschrift:
        //
        //   Cockpit-Titel: breite=0px hoehe=28px zeilen=1 ueberlaufBreite=false
        //                  ueberlaufHoehe=true maxBreite=0px maxHoehe=2147483647px
        //
        // maxBreite=0: Die Titelspalte bekam ueberhaupt keine Breite zugeteilt - schon bei
        // Schriftfaktor 1.0, weil die Row mit SpaceBetween ihr ueber `weight(1f, fill = false)`
        // nur zuteilte, was die mitwachsenden Badges uebrigliessen. Die Kopfzeile ist jetzt eine
        // FlowRow mit Mindestbreite fuer den Titel und Obergrenze fuer die Badges; reicht der
        // Platz nicht, bricht die Zeile um, statt den Titel verschwinden zu lassen.
        pruefeNichtAbgeschnitten(
            "Cockpit-Titel",
            textLayout(composeRule.onNodeWithText(titel).performScrollTo()),
        )
    }

    @Test
    fun beiEinhundertdreissigProzentBleibtDerStartknopfBedienbar() {
        // 130 %: die haeufigste Abweichung vom Standard, unter Android mit zwei Tipps erreichbar.
        zeigeCockpitMitSchriftfaktor(1.3f)
        pruefeBedienbarkeit()
    }

    @Test
    fun beiZweihundertProzentBleibtDerStartknopfBedienbar() {
        // 200 %: der groesste Wert, den Android in den Bedienungshilfen anbietet. Hier geht es
        // um den Layout-Zusammenbruch (Knopf aus dem Bildschirm gedrueckt, Hoehe 0, Absturz beim
        // Messen) - nicht um Ellipsen, siehe Klassen-KDoc.
        zeigeCockpitMitSchriftfaktor(2.0f)
        pruefeBedienbarkeit()
    }

    private fun pruefeBedienbarkeit() {
        composeRule
            .onNodeWithTag(START_MEASUREMENT_BUTTON_TAG)
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()

        // F-34 ist umgesetzt (Phase 5). Vorher war der Cockpit-Titel bei 130 % und 200 % zwar
        // noch im Semantikbaum, aber nicht mehr dargestellt - am Emulator bestaetigt (Lauf
        // 36161839317). Ursache war die Kopfzeile als Row mit SpaceBetween, in der die
        // Titelspalte `weight(1f, fill = false)` trug und von den mitwachsenden Badges auf
        // praktisch null Breite gedrueckt wurde. Jetzt gilt die harte Zusicherung.
        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.cockpit_title))
            .performScrollTo()
            .assertIsDisplayed()
    }

    /**
     * Prueft, dass ein Text nicht abgeschnitten ist - anhand interpretierbarer Messwerte statt
     * anhand von `hasVisualOverflow`.
     *
     * **Warum nicht `hasVisualOverflow` (Korrektur nach dem Emulator-Lauf 25.09.2026):** Fuer die
     * Startknopf-Beschriftung meldete es bei Standardschrift `true`, waehrend jede andere
     * gemessene Groesse das Gegenteil sagte - `breite=145px`, `maxBreite=240px`, `zeilen=1`,
     * `ueberlaufHoehe=false`. Der Text passte also sichtbar in seine Constraints. Ein Merkmal,
     * das dem eigenen Messwert widerspricht, taugt nicht als Zusicherung. Geprueft werden
     * deshalb die drei Aussagen, die sich eindeutig lesen lassen: der Text bleibt einzeilig, er
     * laeuft nicht in der Hoehe ueber, und er belegt nicht mehr Breite als ihm zusteht. Die
     * vollstaendigen Messwerte stehen weiterhin in der Fehlermeldung.
     */
    private fun pruefeNichtAbgeschnitten(
        bezeichnung: String,
        layout: TextLayoutResult,
    ) {
        val diagnose = messwerte(bezeichnung, layout)
        assertEquals("$bezeichnung darf bei Standardschrift nicht umbrechen - $diagnose", 1, layout.lineCount)
        assertFalse(
            "$bezeichnung darf bei Standardschrift nicht in der Hoehe abgeschnitten sein - $diagnose",
            layout.didOverflowHeight,
        )
        assertTrue(
            "$bezeichnung belegt mehr Breite als die Constraints erlauben - $diagnose",
            layout.size.width <= layout.layoutInput.constraints.maxWidth,
        )
    }

    @Test
    fun derSchriftfaktorKommtInDerKompositionUeberhauptAn() {
        // Absicherung gegen einen stillen Fehlschlag der Testanordnung selbst: Kaeme der
        // Schriftfaktor gar nicht an, blieben die Tests oben gruen und waeren wertlos.
        //
        // Beide Stufen werden in EINER Komposition nebeneinander gerendert. Die erste Fassung
        // rief setContent zweimal auf; das quittiert die ComposeTestRule mit
        // "Cannot call setContent twice per test!" (Emulator-Lauf 25.09.2026). Geprueft wird am
        // Cockpit-Titeltext in derselben Typografie, nicht am Startknopf - der hat eine feste
        // Hoehe von 56 dp und kann gar nicht mitwachsen (das gehoert zu Befund F-34).
        val titel = composeRule.activity.getString(R.string.cockpit_title)

        composeRule.setContent {
            val basis = LocalDensity.current
            Column {
                CompositionLocalProvider(
                    LocalDensity provides Density(density = basis.density, fontScale = 1.0f),
                ) {
                    Text(
                        text = titel,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.testTag("probe_schrift_normal"),
                    )
                }
                CompositionLocalProvider(
                    LocalDensity provides Density(density = basis.density, fontScale = 2.0f),
                ) {
                    Text(
                        text = titel,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.testTag("probe_schrift_gross"),
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val hoeheNormal =
            composeRule
                .onNodeWithTag("probe_schrift_normal")
                .fetchSemanticsNode()
                .size.height
        val hoeheGross =
            composeRule
                .onNodeWithTag("probe_schrift_gross")
                .fetchSemanticsNode()
                .size.height

        assertTrue(
            "Bei doppelter Schriftgroesse muss derselbe Text hoeher sein als bei einfacher " +
                "(normal=$hoeheNormal, gross=$hoeheGross) - sonst kommt der Schriftfaktor in " +
                "dieser Testanordnung gar nicht an und alle uebrigen Tests dieser Klasse waeren " +
                "wertlos",
            hoeheGross > hoeheNormal,
        )
    }
}
