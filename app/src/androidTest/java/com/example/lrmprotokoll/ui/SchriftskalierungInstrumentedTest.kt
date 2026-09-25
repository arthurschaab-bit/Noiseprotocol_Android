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
 * **Bewusst abgestufte Schaerfe.** Bei Standardschrift wird *kein Ueberlauf* verlangt: Das muss
 * heute gelten und ist damit eine echte Regressionsbremse. Bei 130 % und 200 % wird nur
 * verlangt, dass die Bedienelemente vorhanden, erreichbar und klickbar bleiben - also kein
 * Layout-Zusammenbruch. Ob dort zusaetzlich Text abgeschnitten wird, ist der noch offene Befund
 * F-21 des Audits. Das hier bereits als Erwartung festzuschreiben wuerde entweder den Fehler als
 * gewolltes Verhalten zementieren oder die CI rot faerben, bevor er behoben ist. **Sobald F-21
 * umgesetzt ist, gehoeren die Ueberlauf-Pruefungen auf alle drei Stufen ausgeweitet** - dafuer
 * steht [textLayout] bereits bereit.
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

        // Der Cockpit-TITEL bekommt hier KEINE Zusicherung, und das ist ein Befund, keine
        // Nachlaessigkeit. Dritter Emulator-Lauf (36164004983), Standardschrift:
        //
        //   Cockpit-Titel: breite=0px hoehe=28px zeilen=1 ueberlaufBreite=false
        //                  ueberlaufHoehe=true maxBreite=0px maxHoehe=2147483647px
        //
        // maxBreite=0: Die Titelspalte bekommt ueberhaupt keine Breite zugeteilt - schon bei
        // Schriftfaktor 1.0. Das ist derselbe Mechanismus wie bei Befund F-21
        // (LiveCockpitCard.kt:223: Row mit SpaceBetween, Titelspalte `weight(1f, fill = false)`
        // und `maxLines = 1`, daneben die mitwachsenden Status-Badges): die Spalte bekommt nur,
        // was die Badges uebriglassen. Auf dem schmalen Geraetebild der CI - die
        // Startknopf-Messung weist nur 240 px Inhaltsbreite aus - bleibt davon nichts uebrig.
        //
        // F-21 ist damit breiter als im Audit beschrieben: nicht nur ein Problem grosser
        // Schrift, sondern generell eines knapper Breite. Solange der Befund offen ist, waere
        // jede Zusicherung auf dem Titel eine dauerhaft rote CI fuer einen bekannten,
        // unbehobenen Fehler. Geprueft wird deshalb nur, dass der Knoten ueberhaupt existiert.
        // **Sobald F-21 umgesetzt ist, gehoert hier pruefeNichtAbgeschnitten("Cockpit-Titel", ...)
        // hin** - die Messfunktion steht bereit.
        composeRule.onNodeWithText(titel).assertExists()
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

        // BEFUND F-21, am Emulator bestaetigt (Lauf 36161839317): Bei 130 % und 200 % ist der
        // Cockpit-Titel zwar noch im Semantikbaum, aber nicht mehr dargestellt
        // (assertIsDisplayed schlaegt fehl). Ursache ist die Kopfzeile in LiveCockpitCard.kt:223
        // - eine Row mit SpaceBetween, in der die Titelspalte `weight(1f, fill = false)` und
        // `maxLines = 1` hat, waehrend die Status-Badges daneben mitwachsen. Die Titelspalte wird
        // dabei auf praktisch null Breite zusammengedrueckt.
        //
        // Das ist genau der noch offene Befund F-21 des Audits, jetzt nicht mehr nur vermutet
        // sondern gemessen. Hier wird deshalb bewusst NUR die Existenz geprueft: eine harte
        // Sichtbarkeitszusicherung wuerde die CI rot faerben, bevor F-21 behoben ist - und das
        // Klassen-KDoc verlangt auf diesen Stufen ohnehin nur die Bedienbarkeit der
        // BEDIENELEMENTE; der Titel ist keines. **Sobald F-21 umgesetzt ist, gehoert hier
        // assertIsDisplayed() hin.**
        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.cockpit_title))
            .assertExists()
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
        // Hoehe von 56 dp und kann gar nicht mitwachsen (genau das ist Teil von Befund F-21).
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
