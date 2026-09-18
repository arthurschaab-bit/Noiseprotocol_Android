package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.data.DiagnosticLogEntity
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regressionstest für M7c Aufgabe 5: DiagnoseScreen zeigte das Diagnose-Log bisher nur als
 * einmaligen Snapshot (LaunchedEffect(Unit)) - ein neuer Eintrag, während der Screen offen war,
 * blieb unsichtbar, bis man ihn schloss und neu öffnete. Jetzt beobachtet der Screen
 * DiagnosticLogDao.alle() als Flow direkt (collectAsState), analog zu NoiseDao.getAll().
 *
 * Läuft gegen die echte, productive DiagnoseScreen()-Funktion mit vollem AppContainer, wie
 * MeterScreenComposeTest/SettingsScreenComposeTest.
 *
 * M12 Schritt 7 (Konzept Abschnitt 2): `DiagnosticLogDao.alle()` (ohne `LIMIT`) wurde durch
 * `neueste(grenze)` ersetzt - derselbe Flow-Mechanismus, aber mit fester Obergrenze. Siehe
 * [DiagnoseLogPaginierungTest] fuer die "Weitere laden"-Gegenprobe.
 *
 * PROMPT_M10_FUNKTIONEN.md F3: die Selbstprüfungs-Checkliste steht seither ganz oben (Index 0
 * der LazyColumn), der hier geprüfte Inhalt ist dadurch auf Index 1 gerutscht - außerhalb des
 * initialen Viewports und außerhalb der Lazy-Prefetch-Reichweite. Deshalb hier erst explizit per
 * Index dorthin scrollen (performScrollToIndex über den DIAGNOSE_LAZY_COLUMN_TAG), statt sich auf
 * performScrollTo() eines noch gar nicht komponierten Knotens zu verlassen.
 *
 * timeoutMillis = 30_000 (ursprünglich 10_000): dasselbe bekannte Timing-Problem wie bei
 * ProtokollDetailScreenComposeTest - auf einem voll ausgelasteten CI-Runner (voller
 * ./gradlew test-Lauf, mehrere Robolectric-JVM-Forks auf begrenzten CPU-Kernen) reicht ein
 * knappes Budget stellenweise nicht aus und der Test schlägt mit ComposeTimeoutException fehl,
 * obwohl er lokal isoliert zuverlässig grün läuft - kein Logikfehler, sondern
 * Ressourcenkonkurrenz auf dem Runner.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiagnoseScreenComposeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun neuerDiagnoseLogEintragErscheintOhneDenScreenNeuZuOeffnen() {
        val container = ApplicationProvider.getApplicationContext<LaermprotokollApp>().container

        runBlocking {
            container.database.diagnosticLogDao().insert(
                // Zeitstempel bewusst weiter in der Zukunft als jede Basis in DiagnosticLogDaoTest/
                // DiagnoseLogPaginierungTest (M12 Schritt 7): die Datenbank ist nicht
                // in-memory-isoliert je Testmethode, und seit Schritt 7 begrenzt neueste() auf die
                // juengsten N Eintraege - ohne einen garantiert juengsten Zeitstempel koennte dieser
                // Eintrag aus dem Anzeigefenster fallen, wenn zuvor andere Tests viele Zeilen
                // eingefuegt haben. Deshalb hier auf den Zeileninhalt statt auf die (bei geteilter
                // Tabelle nicht mehr exakt vorhersagbare) Kopfzeilen-Anzahl gepruefft.
                DiagnosticLogEntity(timestamp = 4_000_000_000_000L, message = "DEGRADED: Testeintrag")
            )
        }

        composeRule.setContent { DiagnoseScreen(onBack = {}) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(DIAGNOSE_LAZY_COLUMN_TAG).performScrollToNode(hasText("DEGRADED: Testeintrag", substring = true))

        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText("DEGRADED: Testeintrag", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("DEGRADED: Testeintrag", substring = true).assertExists()
    }

    @Test
    fun remoteDiagnoseUndSupportBundleExportWerdenGerendert() {
        composeRule.setContent { DiagnoseScreen(onBack = {}) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(DIAGNOSE_LAZY_COLUMN_TAG).performScrollToIndex(1)

        val secPrivacy = composeRule.activity.getString(com.example.lrmprotokoll.R.string.diagnose_remote_privacy_header)
        val sendReports = composeRule.activity.getString(com.example.lrmprotokoll.R.string.diagnose_send_reports)
        val exportBundle = composeRule.activity.getString(com.example.lrmprotokoll.R.string.diagnose_export_bundle)

        // Seit der Versionskennung-Karte (docs/PROMPT_VERSIONSKENNUNG.md Abschnitt 4.5) ist
        // dieses LazyColumn-Item hoeher als der Viewport - performScrollTo() holt den jeweiligen
        // Knoten zusaetzlich in Sicht, statt sich auf performScrollToIndex(1) allein zu verlassen.
        composeRule.onNodeWithText(secPrivacy).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(sendReports).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(exportBundle).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun diagnoseIdWirdMitKopierenButtonAngezeigt() {
        val container = ApplicationProvider.getApplicationContext<LaermprotokollApp>().container
        container.settingsManager.letzteDiagnoseId = "DIA-20260820-TEST9999"

        composeRule.setContent { DiagnoseScreen(onBack = {}) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(DIAGNOSE_LAZY_COLUMN_TAG).performScrollToIndex(1)

        // Seit der Versionskennung-Zeile (docs/PROMPT_VERSIONSKENNUNG.md Abschnitt 4.5) gibt es
        // zwei "Kopieren"-Knoepfe in dieser Karte - testTag statt Text macht diesen hier eindeutig.
        composeRule.onNodeWithText("DIA-20260820-TEST9999").assertIsDisplayed()
        composeRule.onNodeWithTag(DIAGNOSE_ID_KOPIEREN_TAG).assertIsDisplayed()
    }

    @Test
    fun versionskennungWirdMitKopierenButtonAngezeigt() {
        // docs/PROMPT_VERSIONSKENNUNG.md Abschnitt 4.5: dieselbe Karte wie die Diagnose-ID
        // (getestet direkt darueber), damit man im Support-Fall beides zusammen hat.
        composeRule.setContent { DiagnoseScreen(onBack = {}) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(DIAGNOSE_LAZY_COLUMN_TAG).performScrollToIndex(1)

        val kennung =
            com.example.lrmprotokoll.Versionskennung.formatiere(
                com.example.lrmprotokoll.BuildConfig.VERSION_NAME,
                com.example.lrmprotokoll.BuildConfig.VERSION_CODE,
            )
        composeRule.onNodeWithTag(DIAGNOSE_VERSIONSKENNUNG_TEXT_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(kennung).assertIsDisplayed()
        composeRule.onNodeWithTag(DIAGNOSE_VERSIONSKENNUNG_KOPIEREN_TAG).assertIsDisplayed()
    }

    @Test
    fun selbstpruefungsChecklisteStehtGanzObenOhneScrollen() {
        // PROMPT_M10_FUNKTIONEN.md F3 verlangt die Checkliste "ganz oben" - Gegenprobe: schlaegt
        // fehl, wenn sie wieder ans Ende rutscht (kein performScrollToIndex hier, bewusst).
        composeRule.setContent { DiagnoseScreen(onBack = {}) }
        composeRule.waitForIdle()

        val checkTitel = composeRule.activity.getString(com.example.lrmprotokoll.R.string.diagnose_self_check_header)
        composeRule.onNodeWithText(checkTitel).assertIsDisplayed()
    }
}
