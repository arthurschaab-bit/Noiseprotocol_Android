package com.example.lrmprotokoll.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.diagnose.DiagnosticCode
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * M12 Schritt 8 (Konzept Aufgabe 1): "Support-Bundles"-Abschnitt im DiagnoseScreen - Zeitpunkt/
 * Ergebnis des letzten Uploads, Anzahl wartender Bundles in der Outbox, Knopf fuer sofortigen
 * Export + Upload.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiagnoseSupportBundlesAbschnittTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun outboxDir(context: Context) =
        File(context.filesDir, com.example.lrmprotokoll.diagnose.acra.SUPPORT_OUTBOX_DIR)

    /**
     * CI-Fund (23.09.2026, PR #187): ein Knopf-Test darf erst enden, wenn die Knopf-Coroutine
     * fertig ist. Sie laeuft nach dem Kopieren in die Outbox weiter (Upload-Planung,
     * Outbox-Zaehlung, Hinweis) und setzt zuletzt im `finally` `supportBundleAktionLaeuft = false`
     * - unter Robolectric aus einem Hintergrund-Thread, und bei Testende sogar trotz Abbruch.
     * Faellt dieser Schreibzugriff in den Wechsel zum naechsten Test, werden die folgenden
     * Compose-Tests derselben JVM nicht mehr idle (`AppNotIdleException`), so im Volllauf gesehen:
     * direkt danach scheiterten `HomeNavigationComposeTest`, `MeterScreenComposeTest`,
     * `MeterScreenPermissionAndScanTest` und `ReportConfigSettingsTest` - dasselbe Muster wie auf
     * `main` seit #182. Belegt mit einer (nicht committeten) Sonde: Schreibzugriff aus einem
     * Hintergrund-Thread nach Testende -> nachfolgender `HomeNavigationComposeTest` 4/4
     * `AppNotIdleException`, ohne ihn 4/4 gruen.
     *
     * Wieder aktiv mit dem Ausgangstext heisst: der letzte Schreibzugriff ist angewendet.
     */
    private fun warteBisKnopfWiederBereit(knopf: String) {
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.waitForIdle()
            runCatching { composeRule.onNodeWithText(knopf).assertIsEnabled() }.isSuccess
        }
    }

    @Before
    fun aufbauen() {
        outboxDir(ApplicationProvider.getApplicationContext()).deleteRecursively()
    }

    @After
    fun aufraeumen() {
        outboxDir(ApplicationProvider.getApplicationContext()).deleteRecursively()
    }

    @Test
    fun zeigtLetztenUploadUndAnzahlWartenderBundlesAn() {
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        app.container.settingsManager.supportBundleLastUploadAt = 1_700_000_000_000L
        app.container.settingsManager.supportBundleLastUploadMessage = "Erfolgreich: 2026-09-17_230000_absturz.zip"
        outboxDir(app).mkdirs()
        File(outboxDir(app), "2026-09-18_010000_periodisch.zip").writeText("x")

        composeRule.setContent { DiagnoseScreen(onBack = {}) }
        composeRule.waitForIdle()

        val header = composeRule.activity.getString(com.example.lrmprotokoll.R.string.diagnose_support_bundles_header)
        composeRule.onNodeWithTag(DIAGNOSE_LAZY_COLUMN_TAG).performScrollToNode(hasText(header))
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText(header).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(header).assertExists()

        val outboxText = composeRule.activity.getString(com.example.lrmprotokoll.R.string.diagnose_support_bundles_outbox_count, 1)
        composeRule.onNodeWithText(outboxText).assertExists()
        composeRule.onNodeWithText("Erfolgreich: 2026-09-17_230000_absturz.zip", substring = true).assertExists()
    }

    @Test
    fun knopfErstelltEinBundleUndErhoehtDieOutboxAnzahl() {
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()

        // Mit uebergebenem onShowSnackbar statt des Toast-Rueckfalls: Robolectrics Compose-Test-
        // Coroutine-Umgebung hat auf dem nach withContext(Dispatchers.IO) wiederaufgenommenen
        // Dispatcher keinen vorbereiteten Looper, Toast.makeText() wirft dort eine
        // NullPointerException ("Can't toast on a thread that has not called Looper.prepare()").
        // Ein echter Aufrufer (siehe Navigationsgraph) uebergibt ohnehin immer einen echten
        // Snackbar-Callback - der Toast-Rueckfall ist nur fuer Vorschauen/Tests ohne Host gedacht.
        val meldungen = CopyOnWriteArrayList<String>()
        composeRule.setContent { DiagnoseScreen(onBack = {}, onShowSnackbar = { meldungen.add(it) }) }
        composeRule.waitForIdle()

        val knopf = composeRule.activity.getString(com.example.lrmprotokoll.R.string.diagnose_support_bundles_create_and_upload)
        composeRule.onNodeWithTag(DIAGNOSE_LAZY_COLUMN_TAG).performScrollToNode(hasText(knopf))
        composeRule.onNodeWithText(knopf).performClick()

        // Der Hinweis kommt erst nach Upload-Planung und Outbox-Zaehlung - danach nur noch das
        // finally der Knopf-Coroutine, auf das warteBisKnopfWiederBereit wartet.
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.waitForIdle()
            meldungen.isNotEmpty()
        }
        warteBisKnopfWiederBereit(knopf)
        val dateien = outboxDir(app).listFiles { f -> f.name.endsWith(".zip") }.orEmpty()
        assertTrue("Der Knopf muss ein Bundle in die Outbox legen", dateien.isNotEmpty())
    }

    /**
     * Owner-Freigabe 23.09.2026 (Folge-PR zu #182): der Fehlerzweig des Sofortupload-Knopfs
     * zeigte nur einen Toast - die eigentliche Ausnahme blieb unsichtbar (unter Robolectric warf
     * der Toast selbst eine NullPointerException und verdeckte sie). Jetzt muss sie gemeldet und
     * ueber denselben Snackbar-Weg wie im Erfolgsfall angezeigt werden.
     */
    @Test
    fun fehlerBeimSofortuploadWirdGemeldetUndAngezeigt() {
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        // Eine Datei an der Stelle des Outbox-Ordners laesst das Kopieren in die Outbox
        // scheitern - ein reproduzierbarer Fehler im try-Zweig des Knopfs.
        outboxDir(app).writeText("keine Outbox")
        val meldungen = CopyOnWriteArrayList<String>()

        composeRule.setContent { DiagnoseScreen(onBack = {}, onShowSnackbar = { meldungen.add(it) }) }
        composeRule.waitForIdle()

        val knopf = composeRule.activity.getString(com.example.lrmprotokoll.R.string.diagnose_support_bundles_create_and_upload)
        composeRule.scrolleZuSobaldGeladen(DIAGNOSE_LAZY_COLUMN_TAG, hasText(knopf))
        composeRule.onNodeWithText(knopf).performClick()

        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.waitForIdle()
            meldungen.any { it.startsWith("Fehlgeschlagen") }
        }
        warteBisKnopfWiederBereit(knopf)
        assertTrue(
            "Die Ausnahme muss als SUPPORT_BUNDLE_FAILED gemeldet werden",
            app.container.diagnosticsReporter.recentEvents().any { it.code == DiagnosticCode.SUPPORT_BUNDLE_FAILED },
        )
    }
}
