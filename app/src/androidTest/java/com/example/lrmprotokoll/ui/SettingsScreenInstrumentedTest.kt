package com.example.lrmprotokoll.ui

import android.app.Activity
import android.app.Instrumentation
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.anyIntent
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.alert.Alert
import com.example.lrmprotokoll.alert.AlertKind
import com.example.lrmprotokoll.alert.AlertReason
import com.example.lrmprotokoll.alert.local.LocalNotificationAlertChannel
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: LaermprotokollApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        LocalNotificationAlertChannel.stoppeAlarmTon(app)
    }

    private fun schreibeSemantikDiagnose(bezeichnung: String) {
        // Der Semantics-Tree ist nur im Compose-Testprozess verfügbar, nicht über ADB.
        // Bei einem CI-Fehler wird er daher in Logcat geschrieben und von der Fehlerdiagnose
        // als logcat-failure.txt bzw. nach PR #154 auch als vollständiges Logcat gesichert.
        runCatching {
            composeRule.onRoot().printToLog("ComposeSemantik-$bezeichnung-zusammengefuehrt")
            composeRule.onRoot(useUnmergedTree = true)
                .printToLog("ComposeSemantik-$bezeichnung-unmerged")
        }
    }

    @Test
    fun settingsScreenZeigtTitelUndScrolltBisZumEnde() {
        var backed = false
        composeRule.setContent { SettingsScreen(onBack = { backed = true }) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(composeRule.activity.getString(com.example.lrmprotokoll.R.string.nav_settings)).assertIsDisplayed()
        composeRule.onNodeWithTag(BILDSCHIRM_ENDE_TAG).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription(composeRule.activity.getString(com.example.lrmprotokoll.R.string.action_back))
            .assertIsDisplayed().performClick()
        assertTrue(backed)
    }

    @Test
    fun settingsScreenErlaubtAbtastrateAuswahlUndSchalterBedienung() {
        composeRule.setContent { SettingsScreen(onBack = {}) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("btn_settings_mode_pro").performClick()
        val secThresholds = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_section_thresholds)
        composeRule.onNodeWithText(secThresholds, substring = true).performScrollTo().performClick()
        composeRule.onNodeWithText(composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_sample_rate_16k), substring = true)
            .performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithText(composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_sample_rate_44k), substring = true)
            .performScrollTo().assertIsDisplayed().performClick()

        val secAi = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_ai_title)
        composeRule.onAllNodesWithText(secAi, substring = true).onFirst().performScrollTo().performClick()
        composeRule.onAllNodesWithText(secAi, substring = true).onFirst().assertIsDisplayed()
    }

    @Test
    fun schwellenSliderPersistiertMinimumUndMaximum() {
        val settingsManager = app.container.settingsManager
        val oldPro = settingsManager.isProMode
        val oldThreshold = settingsManager.dbThreshold
        try {
            settingsManager.isProMode = true
            settingsManager.dbThreshold = 65f

            composeRule.setContent { SettingsScreen(onBack = {}) }
            composeRule.waitForIdle()
            val sectionTitle = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_section_thresholds)
            composeRule.onNodeWithText(sectionTitle, substring = true).performScrollTo().performClick()

            val slider = composeRule.onNodeWithTag("slider_db_threshold").performScrollTo().assertIsDisplayed()
            slider.performTouchInput { swipeLeft() }
            composeRule.waitForIdle()
            assertEquals(30f, settingsManager.dbThreshold, 0.6f)

            slider.performTouchInput { swipeRight() }
            composeRule.waitForIdle()
            assertEquals(100f, settingsManager.dbThreshold, 0.6f)
        } finally {
            settingsManager.dbThreshold = oldThreshold
            settingsManager.isProMode = oldPro
        }
    }

    /**
     * Geraetetest-Checkliste F6: der Speicherplatz-Abschnitt (F5) war bislang kompiliert und
     * lint-sauber, aber noch nie auf einem echten Geraet gesehen worden - `ermittleSpeicherplatz()`
     * liest echte Dateigroessen vom Dateisystem, genau das laeuft unter Robolectric nie mit.
     */
    @Test
    fun speicherplatzAbschnittZeigtErmittelteGroessenNachDemAufklappen() {
        val settingsManager = app.container.settingsManager
        val oldPro = settingsManager.isProMode
        try {
            settingsManager.isProMode = true

            composeRule.setContent { SettingsScreen(onBack = {}, initialTab = SettingsTab.DATEN) }
            composeRule.waitForIdle()

            // Bug (PR #150): der CI-Emulator startet nicht immer mit deutscher Geraete-Locale -
            // ein hartcodiertes deutsches Literal wie "Audiodateien:" existiert dann schlicht nicht
            // im Baum (siehe values-en/strings.xml), waehrend andere Strings deutsch bleiben, wenn
            // sie nicht ueber stringResource() laufen. Deshalb ueber getString() aufloesen statt
            // hartzukodieren - funktioniert unabhaengig von der tatsaechlichen Geraete-Locale.
            val audioLabel = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_cleanup_storage_audio)
                .substringBefore("%1\$s").trim()
            val dbLabel = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_cleanup_storage_database)
                .substringBefore("%1\$s").trim()
            val sectionTitle = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_cleanup_title)
            composeRule.onNodeWithText(sectionTitle, substring = true).performScrollTo().performClick()
            composeRule.waitForIdle()

            // Zwischenpruefung, unabhaengig von ermittleSpeicherplatz(): der Titel taucht nach dem
            // Aufklappen ein zweites Mal auf (Schalterzeile im Kartenkoerper), das haengt nur an
            // expRetention, nicht am asynchron ermittelten Speicherplatz. Bestaetigt das Aufklappen
            // selbst und grenzt einen etwaigen erneuten Fehlschlag auf ermittleSpeicherplatz() ein.
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                composeRule.onAllNodesWithText(sectionTitle, substring = true).fetchSemanticsNodes().size == 2
            }

            // Der Isolationstest SpeicherplatzUebersichtInstrumentedTest belegt: ermittleSpeicherplatz()
            // selbst ist schnell (<2s). Trotzdem haengt genau dieser LaunchedEffect(expRetention) auf
            // dem CI-Emulator zuverlaessig fest, obwohl er (siehe Zwischenpruefung oben) nachweislich
            // gestartet wird. Zwei gezielte Fixversuche (waitForIdle() direkt nach dem Klick,
            // mainClock.advanceTimeBy(1_000L) fuer die Card-Animation) haben den exakt gleichen
            // 15s-Timeout nicht behoben - der Fehlerort liegt also woanders. Statt eines dritten
            // blinden Fixversuchs: manuelle Poll-Schleife, die im Fehlerfall mitliefert, ob der
            // Abschnitt zu diesem Zeitpunkt ueberhaupt noch aufgeklappt ist (2 Titel-Treffer) - das
            // grenzt zwischen "wieder eingeklappt" und "aufgeklappt, aber Ergebnis fehlt" ein.
            val deadline = System.currentTimeMillis() + 15_000L
            var sichtbarBeimLetztenVersuch = false
            var expandiertBeimLetztenVersuch = false
            while (System.currentTimeMillis() < deadline) {
                composeRule.waitForIdle()
                sichtbarBeimLetztenVersuch =
                    composeRule.onAllNodesWithText(audioLabel, substring = true).fetchSemanticsNodes().isNotEmpty()
                if (sichtbarBeimLetztenVersuch) break
                expandiertBeimLetztenVersuch =
                    composeRule.onAllNodesWithText(sectionTitle, substring = true).fetchSemanticsNodes().size == 2
                Thread.sleep(200)
            }
            if (!sichtbarBeimLetztenVersuch) {
                schreibeSemantikDiagnose("SettingsSpeicherplatz")
            }
            assertTrue(
                "Nach 15s kein '$audioLabel' sichtbar. Abschnitt beim letzten Poll " +
                    "${if (expandiertBeimLetztenVersuch) "noch aufgeklappt (2 Titel-Treffer)" else "NICHT mehr aufgeklappt"}.",
                sichtbarBeimLetztenVersuch,
            )
            composeRule.onNodeWithText(audioLabel, substring = true).performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText(dbLabel, substring = true).performScrollTo().assertIsDisplayed()
        } finally {
            settingsManager.isProMode = oldPro
        }
    }

    /**
     * Checkliste Button/Screen-Coverage Phase 6.2 (Risiko zuerst): der Auto-Bereinigung-Schalter
     * (F5) wurde bislang nie tatsaechlich geklickt - alle bisherigen Tests setzten
     * `autoRetentionEnabled` nur direkt im Code. Das ist der einzige Schalter der App, der
     * *automatisch und wiederkehrend* Aufnahmen in den Papierkorb verschiebt, deshalb der
     * Vorschau-Dialog davor (PROMPT_M10_FUNKTIONEN.md F5) - genau dieser Schutz war ungetestet.
     */
    @Test
    fun autoBereinigungVorschauZeigtEchteKandidatenUndAktivierenSchaltetEin() {
        val settingsManager = app.container.settingsManager
        val oldPro = settingsManager.isProMode
        val oldEnabled = settingsManager.autoRetentionEnabled
        val oldDays = settingsManager.autoRetentionDays
        val alterZeitpunkt = System.currentTimeMillis() - 200L * 24 * 60 * 60 * 1000
        try {
            settingsManager.isProMode = true
            settingsManager.autoRetentionEnabled = false
            settingsManager.autoRetentionDays = 90

            runBlocking {
                app.container.database.noiseDao().insert(
                    com.example.lrmprotokoll.data.NoiseRecord(
                        timestamp = alterZeitpunkt,
                        amplitude = 0.0,
                        dbValue = 55.0,
                        filePath = "/tmp/nicht-vorhanden-retention-test.wav",
                    )
                )
            }
            // Nicht auf "genau 1 Kandidat" verlassen: die App-DB wird zwischen den ueber 160
            // Tests dieser Instrumentierungs-Session nicht zurueckgesetzt (kein clearAllTables()
            // in @Before/@After dieser Klasse), andere Tests koennen bereits aeltere,
            // unmarkierte Aufnahmen hinterlassen haben. Stattdessen dieselbe Funktion wie die
            // Produktion aufrufen und den Dialog gegen deren echtes Ergebnis pruefen.
            val erwarteteVorschau = runBlocking {
                com.example.lrmprotokoll.messreihe.ermittleRetentionVorschau(
                    app.container.database.noiseDao(),
                    settingsManager.autoRetentionDays.toInt(),
                )
            }

            composeRule.setContent { SettingsScreen(onBack = {}, initialTab = SettingsTab.DATEN) }
            composeRule.waitForIdle()

            val sectionTitle = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_cleanup_title)
            composeRule.onNodeWithText(sectionTitle, substring = true).performScrollTo().performClick()
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("switch_auto_retention").performScrollTo().performClick()

            val dialogTitle = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_cleanup_preview_title)
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                composeRule.onAllNodesWithText(dialogTitle).fetchSemanticsNodes().isNotEmpty()
            }
            // Echte Kandidatenzahl aus der DB, kein erfundener Platzhaltertext - der Dialog muss
            // mindestens die zuvor eingefuegte, alte, unmarkierte, nicht-favorisierte Aufnahme
            // mitzaehlen (assertTrue statt exaktem Text, siehe Kommentar oben zur DB-Isolation).
            // Der komplette erwartete Satz wird ueber getString() mit denselben Argumenten wie
            // die Produktion aufgebaut, NICHT als deutscher Teilstring hartkodiert: der
            // CI-Emulator bootet die Geraetesprache nicht deterministisch (en-US oder de je nach
            // Lauf, siehe PR #150/#153) - "settings_cleanup_preview_text" hat eine echte
            // values-en-Uebersetzung ("... recordings older than ..."), ein deutscher
            // Teilstring wie "1 Aufnahmen" existiert auf einem englischsprachigen Emulator gar
            // nicht im Baum, was den vorherigen CI-Fehler "could not find any node" erklaert.
            val erwarteterDialogText = composeRule.activity.getString(
                com.example.lrmprotokoll.R.string.settings_cleanup_preview_text,
                erwarteteVorschau.anzahlAufnahmen,
                settingsManager.autoRetentionDays.toInt(),
                com.example.lrmprotokoll.messreihe.formatiereBytes(erwarteteVorschau.audioBytes),
            )
            // Anders als der (laengere) Dialog in GesamtberichtStammdatenSheetInstrumentedTest.kt
            // (PR #156) hat dieser Dialogtext KEIN scrollbares Elternlayout - ein CI-Lauf hat das
            // bewiesen ("Semantic Node has no parent layout with a Scroll SemanticsAction"),
            // performScrollTo() ist hier also schlicht falsch, nicht nur unnoetig, und wurde
            // deshalb entfernt.
            //
            // Owner-Entscheidung nach Eskalation (PR #159): letzter Fixversuch fuer das
            // verbleibende "not displayed" aus CI-Runde 1. Hypothese: der Dialogtitel und der
            // (laengere) Dialogtext werden zwar in derselben AlertDialog-Komposition gesetzt,
            // koennen aber in getrennten Frames sichtbar/layoutet werden - das bisherige
            // waitUntil() wartete nur auf den TITEL, nicht auf den TEXT selbst. Jetzt wie beim
            // Titel per waitUntil() auf den Textknoten pollen, bevor assertIsDisplayed() greift.
            assertTrue("Es muss mindestens die eine eingefuegte alte Aufnahme als Kandidat zaehlen", erwarteteVorschau.anzahlAufnahmen >= 1)
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                composeRule.onAllNodesWithText(erwarteterDialogText, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(erwarteterDialogText, substring = true).assertIsDisplayed()

            val confirmText = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_cleanup_preview_confirm)
            composeRule.onNodeWithText(confirmText).performClick()

            assertTrue(
                "Bestaetigen im Vorschau-Dialog muss die Auto-Bereinigung tatsaechlich aktivieren",
                settingsManager.autoRetentionEnabled,
            )
        } finally {
            settingsManager.autoRetentionEnabled = oldEnabled
            settingsManager.autoRetentionDays = oldDays
            settingsManager.isProMode = oldPro
        }
    }

    @Test
    fun autoBereinigungVorschauAbbrechenLaesstEinstellungAusgeschaltet() {
        val settingsManager = app.container.settingsManager
        val oldPro = settingsManager.isProMode
        val oldEnabled = settingsManager.autoRetentionEnabled
        try {
            settingsManager.isProMode = true
            settingsManager.autoRetentionEnabled = false

            composeRule.setContent { SettingsScreen(onBack = {}, initialTab = SettingsTab.DATEN) }
            composeRule.waitForIdle()

            val sectionTitle = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_cleanup_title)
            composeRule.onNodeWithText(sectionTitle, substring = true).performScrollTo().performClick()
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("switch_auto_retention").performScrollTo().performClick()

            val dialogTitle = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_cleanup_preview_title)
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                composeRule.onAllNodesWithText(dialogTitle).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(composeRule.activity.getString(com.example.lrmprotokoll.R.string.action_cancel)).performClick()

            composeRule.waitForIdle()
            assertFalse(
                "Abbrechen im Vorschau-Dialog darf die Auto-Bereinigung nicht aktivieren",
                settingsManager.autoRetentionEnabled,
            )
            composeRule.onNodeWithTag("switch_auto_retention").assertIsOff()
        } finally {
            settingsManager.autoRetentionEnabled = oldEnabled
            settingsManager.isProMode = oldPro
        }
    }

    /**
     * Checkliste Button/Screen-Coverage Phase 6.2 (Risiko zuerst): "Speicher freigeben" loescht
     * echte Dateien unwiderruflich - bislang gab es dafuer ueberhaupt keinen Test, weder fuer die
     * Bestaetigung noch fuers Abbrechen. Reine Datei-/DB-Logik ohne Netz/GPS, deshalb ohne Fakes
     * direkt gegen ein echtes Dummy-File im externen App-Verzeichnis testbar.
     */
    @Test
    fun speicherFreigebenLoeschtAusgewaehlteAlteDateiNachBestaetigung() {
        val verzeichnis = app.getExternalFilesDir(null)!!
        val datei = File(verzeichnis, "retention_test_alt.wav")
        datei.writeBytes(ByteArray(1024))
        datei.setLastModified(System.currentTimeMillis() - 100L * 24 * 60 * 60 * 1000)

        try {
            composeRule.setContent { SettingsScreen(onBack = {}, initialTab = SettingsTab.DATEN) }
            composeRule.waitForIdle()

            composeRule.onNodeWithText("Speicherplatz", substring = true).performScrollTo().performClick()
            composeRule.waitForIdle()

            composeRule.onNodeWithText("Audioaufnahmen (WAV)").performScrollTo().performClick()
            composeRule.onNodeWithText("alles").performScrollTo().performClick()
            composeRule.onNodeWithText("Freigeben …").performScrollTo().performClick()

            composeRule.waitUntil(timeoutMillis = 5_000L) {
                composeRule.onAllNodesWithText("Speicher freigeben?").fetchSemanticsNodes().isNotEmpty()
            }
            // Echte Vorschau-Zahl, kein Platzhalter - genau unsere eine Testdatei.
            composeRule.onNodeWithText("1 Dateien", substring = true).assertIsDisplayed()

            composeRule.onNodeWithText("Endgültig löschen").performClick()

            composeRule.waitUntil(timeoutMillis = 5_000L) { !datei.exists() }
            assertTrue("Datei muss nach Bestaetigung tatsaechlich geloescht sein", !datei.exists())
        } finally {
            datei.delete()
        }
    }

    @Test
    fun speicherFreigebenAbbrechenLoeschtNichts() {
        val verzeichnis = app.getExternalFilesDir(null)!!
        val datei = File(verzeichnis, "retention_test_abbrechen.wav")
        datei.writeBytes(ByteArray(1024))
        datei.setLastModified(System.currentTimeMillis() - 100L * 24 * 60 * 60 * 1000)

        try {
            composeRule.setContent { SettingsScreen(onBack = {}, initialTab = SettingsTab.DATEN) }
            composeRule.waitForIdle()

            composeRule.onNodeWithText("Speicherplatz", substring = true).performScrollTo().performClick()
            composeRule.waitForIdle()

            composeRule.onNodeWithText("Audioaufnahmen (WAV)").performScrollTo().performClick()
            composeRule.onNodeWithText("alles").performScrollTo().performClick()
            composeRule.onNodeWithText("Freigeben …").performScrollTo().performClick()

            composeRule.waitUntil(timeoutMillis = 5_000L) {
                composeRule.onAllNodesWithText("Speicher freigeben?").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(composeRule.activity.getString(com.example.lrmprotokoll.R.string.action_cancel)).performClick()
            composeRule.waitForIdle()

            assertTrue("Abbrechen darf die Datei nicht loeschen", datei.exists())
        } finally {
            datei.delete()
        }
    }

    /**
     * Checkliste Button/Screen-Coverage Phase 6.2 (Risiko zuerst, nach PR #159): der
     * "Audio-Aufnahmen (WAV) speichern"-Schalter ist der Datenschutz-Kernschalter aus
     * PROMPT_M10_FUNKTIONEN.md (DSGVO-Modus: reine Pegelmessung ohne Audio) - bislang nie
     * tatsaechlich geklickt. Die Kartenzusammenfassung ist ein hartkodierter Kotlin-Literal
     * (kein stringResource), daher hier bewusst ohne getString() geprueft.
     */
    @Test
    fun recordWavAudioSchalterAendertEinstellungUndSectionSummary() {
        val settingsManager = app.container.settingsManager
        val oldValue = settingsManager.recordWavAudio
        try {
            settingsManager.recordWavAudio = true

            composeRule.setContent { SettingsScreen(onBack = {}, initialTab = SettingsTab.START) }
            composeRule.waitForIdle()

            composeRule.onNodeWithText("WAV-Audio aktiv", substring = true).assertIsDisplayed()

            val sectionTitle = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_section_thresholds)
            composeRule.onNodeWithText(sectionTitle, substring = true).performScrollTo().performClick()
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("switch_record_wav_audio").performScrollTo().assertIsOn()
            composeRule.onNodeWithTag("switch_record_wav_audio").performClick()

            assertFalse("Schalter muss recordWavAudio tatsaechlich ausschalten", settingsManager.recordWavAudio)
            composeRule.onNodeWithTag("switch_record_wav_audio").assertIsOff()
            composeRule.onNodeWithText("Reine Pegelmessung (Kein Audio)", substring = true).assertIsDisplayed()
        } finally {
            settingsManager.recordWavAudio = oldValue
        }
    }

    /**
     * Checkliste Button/Screen-Coverage Phase 6.2: der Alarmierungs-Grundschalter war bislang
     * nie geklickt. Prueft echte Wirkung statt nur des Settings-Felds: im Pro-Modus blendet das
     * Einschalten den ntfy-Schalter (bereits eigenes testTag "switch_ntfy_enabled") ein, das
     * Ausschalten blendet ihn wieder aus - das ist die tatsaechliche `if (alarmierungAktiv)`-
     * Bedingung in SettingsScreen.kt, nicht nur der Zustand von SettingsManager.
     */
    @Test
    fun alarmierungAktivSchalterSchaltetEinUndZeigtProOptionenAn() {
        val settingsManager = app.container.settingsManager
        val oldPro = settingsManager.isProMode
        val oldAlarm = settingsManager.alarmierungAktiv
        try {
            settingsManager.isProMode = true
            settingsManager.alarmierungAktiv = false

            composeRule.setContent { SettingsScreen(onBack = {}, initialTab = SettingsTab.START) }
            composeRule.waitForIdle()

            val sectionTitle = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_alerting_title)
            composeRule.onNodeWithText(sectionTitle, substring = true).performScrollTo().performClick()
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("switch_alarmierung_aktiv").performScrollTo().assertIsOff()
            composeRule.onAllNodesWithTag("switch_ntfy_enabled").assertCountEquals(0)

            composeRule.onNodeWithTag("switch_alarmierung_aktiv").performClick()
            assertTrue("Schalter muss alarmierungAktiv tatsaechlich einschalten", settingsManager.alarmierungAktiv)
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                composeRule.onAllNodesWithTag("switch_ntfy_enabled").fetchSemanticsNodes().isNotEmpty()
            }
            // Das Aufklappen des Pro-Bereichs verschiebt den ntfy-Schalter nach unten - er kann
            // dadurch ausserhalb des aktuellen Sichtbereichs liegen, obwohl er schon im
            // Semantics-Baum steht (waitUntil oben reicht dafuer nicht). Anders als der
            // Dialoginhalt in PR #159 liegt dieser Schalter im scrollbaren Hauptbereich der
            // Settings-Seite, dort hat performScrollTo() an mehreren Stellen bereits zuverlaessig
            // funktioniert (z.B. switch_auto_retention).
            composeRule.onNodeWithTag("switch_ntfy_enabled").performScrollTo().assertIsDisplayed()

            composeRule.onNodeWithTag("switch_alarmierung_aktiv").performClick()
            assertFalse("Schalter muss alarmierungAktiv tatsaechlich ausschalten", settingsManager.alarmierungAktiv)
            composeRule.onAllNodesWithTag("switch_ntfy_enabled").assertCountEquals(0)
        } finally {
            settingsManager.alarmierungAktiv = oldAlarm
            settingsManager.isProMode = oldPro
        }
    }

    /**
     * Checkliste Button/Screen-Coverage Phase 6.2: der Ruhezeiten-Grundschalter (F8) war
     * bislang nie geklickt. Prueft echte Wirkung: Einschalten blendet den
     * Messgeraet-Ruhezeit-Schwellenwert-Slider (testTag "slider_meter_quiet_hours_threshold")
     * ein, Ausschalten blendet ihn wieder aus.
     */
    @Test
    fun quietHoursEnabledSchalterSchaltetEinUndZeigtSchwellenwertSlider() {
        val settingsManager = app.container.settingsManager
        val oldEnabled = settingsManager.quietHoursEnabled
        try {
            settingsManager.quietHoursEnabled = false

            composeRule.setContent { SettingsScreen(onBack = {}, initialTab = SettingsTab.START) }
            composeRule.waitForIdle()

            val sectionTitle = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_quiet_hours_title)
            composeRule.onNodeWithText(sectionTitle, substring = true).performScrollTo().performClick()
            composeRule.waitForIdle()

            composeRule.onAllNodesWithTag("slider_meter_quiet_hours_threshold").assertCountEquals(0)
            composeRule.onNodeWithTag("switch_quiet_hours_enabled").performScrollTo().assertIsOff()

            composeRule.onNodeWithTag("switch_quiet_hours_enabled").performClick()
            assertTrue("Schalter muss quietHoursEnabled tatsaechlich einschalten", settingsManager.quietHoursEnabled)
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                composeRule.onAllNodesWithTag("slider_meter_quiet_hours_threshold").fetchSemanticsNodes().isNotEmpty()
            }
            // Gleicher Grund wie beim ntfy-Schalter oben: der Slider erscheint erst nach dem
            // Einschalten weiter unten im scrollbaren Hauptbereich.
            composeRule.onNodeWithTag("slider_meter_quiet_hours_threshold").performScrollTo().assertIsDisplayed()

            composeRule.onNodeWithTag("switch_quiet_hours_enabled").performClick()
            assertFalse("Schalter muss quietHoursEnabled tatsaechlich ausschalten", settingsManager.quietHoursEnabled)
            composeRule.onAllNodesWithTag("slider_meter_quiet_hours_threshold").assertCountEquals(0)
        } finally {
            settingsManager.quietHoursEnabled = oldEnabled
        }
    }

    /**
     * Echtes Geraete-Pendant zu [MeterSchwellenwertUiTest] (Robolectric, app/src/test) - Teil
     * der Bestandsaufnahme nach dem Datumsbereich-Dialog-Bug (Owner-Auftrag 15.09.2026). Prueft-
     * protokoll 11.09.2026 Frage 4 (Korrekturliste C-3): der eigene Messgeraet-Schwellenwert war
     * bisher nur in SettingsManager/MeterTriggerSource erreichbar, nicht ueber die UI.
     */
    @Test
    fun derMessgeraetSchwellenwertTagIstNebenDemMikrofonReglerErreichbar() {
        composeRule.setContent { SettingsScreen(onBack = {}) }
        composeRule.waitForIdle()

        val titel = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_section_thresholds)
        composeRule.onNodeWithText(titel, substring = true).performScrollTo().performClick()

        composeRule.onNodeWithTag("slider_meter_db_threshold").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun derMessgeraetRuhezeitSchwellenwertIstBeiAktivenRuhezeitenErreichbar() {
        val settingsManager = app.container.settingsManager
        val oldQuietHours = settingsManager.quietHoursEnabled
        try {
            settingsManager.quietHoursEnabled = true

            composeRule.setContent { SettingsScreen(onBack = {}) }
            composeRule.waitForIdle()

            val titel = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_quiet_hours_title)
            composeRule.onNodeWithText(titel, substring = true).performScrollTo().performClick()

            composeRule.onNodeWithTag("slider_meter_quiet_hours_threshold").performScrollTo().assertIsDisplayed()
        } finally {
            settingsManager.quietHoursEnabled = oldQuietHours
        }
    }

    @Test
    fun ntfyErzeugtBeimErstenAktivierenAutomatischEinTopicUndZeigtEsAn() {
        val settingsManager = app.container.settingsManager
        val oldPro = settingsManager.isProMode
        val oldAlerting = settingsManager.alarmierungAktiv
        val oldNtfyActive = settingsManager.ntfyAktiv
        val oldTopic = settingsManager.ntfyTopic
        try {
            settingsManager.isProMode = true
            settingsManager.alarmierungAktiv = true
            settingsManager.ntfyAktiv = false
            settingsManager.ntfyTopic = ""

            composeRule.setContent { SettingsScreen(onBack = {}) }
            composeRule.waitForIdle()
            val title = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_alerting_title)
            composeRule.onNodeWithText(title, substring = true).performScrollTo().performClick()
            composeRule.onNodeWithTag("switch_ntfy_enabled").performScrollTo().assertIsDisplayed().performClick()

            composeRule.waitUntil(timeoutMillis = 5_000L) { settingsManager.ntfyTopic.isNotBlank() }
            val generatedTopic = settingsManager.ntfyTopic
            composeRule.onNodeWithTag("input_ntfy_topic").performScrollTo().assertTextContains(generatedTopic)
        } finally {
            settingsManager.ntfyTopic = oldTopic
            settingsManager.ntfyAktiv = oldNtfyActive
            settingsManager.alarmierungAktiv = oldAlerting
            settingsManager.isProMode = oldPro
        }
    }

    @Test
    fun ntfyBelaesstVorhandenesTopicBeimAktivierenUnveraendert() {
        val settingsManager = app.container.settingsManager
        val oldPro = settingsManager.isProMode
        val oldAlerting = settingsManager.alarmierungAktiv
        val oldNtfyActive = settingsManager.ntfyAktiv
        val oldTopic = settingsManager.ntfyTopic
        val existingTopic = "bestehendes-test-topic"
        try {
            settingsManager.isProMode = true
            settingsManager.alarmierungAktiv = true
            settingsManager.ntfyAktiv = false
            settingsManager.ntfyTopic = existingTopic

            composeRule.setContent { SettingsScreen(onBack = {}) }
            composeRule.waitForIdle()
            val title = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_alerting_title)
            composeRule.onNodeWithText(title, substring = true).performScrollTo().performClick()
            composeRule.onNodeWithTag("switch_ntfy_enabled").performScrollTo().assertIsDisplayed().performClick()

            composeRule.waitForIdle()
            assertEquals(existingTopic, settingsManager.ntfyTopic)
            composeRule.onNodeWithTag("input_ntfy_topic").performScrollTo().assertTextContains(existingTopic)
        } finally {
            settingsManager.ntfyTopic = oldTopic
            settingsManager.ntfyAktiv = oldNtfyActive
            settingsManager.alarmierungAktiv = oldAlerting
            settingsManager.isProMode = oldPro
        }
    }

    /**
     * Checkliste Button/Screen-Coverage Phase 6.2 (Risiko zuerst), naechste Stufe nach PR #161:
     * der ntfy-Server-Textfeld (Pendant zu input_ntfy_topic, das bereits getestet ist) war
     * bislang nie tatsaechlich bearbeitet worden. Zustand direkt vorgesetzt (ntfyAktiv = true),
     * damit das Feld schon bei der ersten Komposition sichtbar ist - kein Klick, der es erst
     * nachtraeglich einblendet (siehe PR #161-Lehre zu performScrollTo() bei nachtraeglich
     * erscheinenden Elementen).
     */
    @Test
    fun ntfyServerFeldKannBearbeitetWerdenUndUebernimmtEingabe() {
        val settingsManager = app.container.settingsManager
        val oldPro = settingsManager.isProMode
        val oldAlerting = settingsManager.alarmierungAktiv
        val oldNtfyActive = settingsManager.ntfyAktiv
        val oldServer = settingsManager.ntfyServer
        try {
            settingsManager.isProMode = true
            settingsManager.alarmierungAktiv = true
            settingsManager.ntfyAktiv = true

            composeRule.setContent { SettingsScreen(onBack = {}) }
            composeRule.waitForIdle()
            val title = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_alerting_title)
            composeRule.onNodeWithText(title, substring = true).performScrollTo().performClick()
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("input_ntfy_server").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("input_ntfy_server").performTextClearance()
            composeRule.onNodeWithTag("input_ntfy_server").performTextInput("https://ntfy.example.test")

            assertEquals("https://ntfy.example.test", settingsManager.ntfyServer)
            composeRule.onNodeWithTag("input_ntfy_server").assertTextContains("https://ntfy.example.test")
        } finally {
            settingsManager.ntfyServer = oldServer
            settingsManager.ntfyAktiv = oldNtfyActive
            settingsManager.alarmierungAktiv = oldAlerting
            settingsManager.isProMode = oldPro
        }
    }

    /**
     * Checkliste Button/Screen-Coverage Phase 6.2: der Karenzzeit-Slider (Verzoegerung vor der
     * Alarmierung nach Verbindungsabbruch) war bislang nie tatsaechlich bedient worden. Zustand
     * direkt vorgesetzt (isProMode/alarmierungAktiv = true), damit der Slider schon bei der
     * ersten Komposition sichtbar ist.
     */
    @Test
    fun karenzzeitSliderPersistiertMinimumUndMaximum() {
        val settingsManager = app.container.settingsManager
        val oldPro = settingsManager.isProMode
        val oldAlerting = settingsManager.alarmierungAktiv
        val oldKarenzzeit = settingsManager.karenzzeitSekunden
        try {
            settingsManager.isProMode = true
            settingsManager.alarmierungAktiv = true
            settingsManager.karenzzeitSekunden = 60

            composeRule.setContent { SettingsScreen(onBack = {}) }
            composeRule.waitForIdle()
            val title = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_alerting_title)
            composeRule.onNodeWithText(title, substring = true).performScrollTo().performClick()
            composeRule.waitForIdle()

            val slider = composeRule.onNodeWithTag("slider_karenzzeit").performScrollTo().assertIsDisplayed()
            slider.performTouchInput { swipeLeft() }
            composeRule.waitForIdle()
            assertEquals(10, settingsManager.karenzzeitSekunden)

            slider.performTouchInput { swipeRight() }
            composeRule.waitForIdle()
            assertEquals(900, settingsManager.karenzzeitSekunden)
        } finally {
            settingsManager.karenzzeitSekunden = oldKarenzzeit
            settingsManager.alarmierungAktiv = oldAlerting
            settingsManager.isProMode = oldPro
        }
    }

    /**
     * Checkliste Button/Screen-Coverage Phase 6.2: der Alarmton-Schalter (akustisches Signal
     * zusaetzlich zu Notification/Vibration bei Verbindungsabbruch) war bislang nie tatsaechlich
     * geklickt worden.
     */
    @Test
    fun alarmTonAktivSchalterAendertEinstellung() {
        val settingsManager = app.container.settingsManager
        val oldPro = settingsManager.isProMode
        val oldAlerting = settingsManager.alarmierungAktiv
        val oldAlarmTon = settingsManager.alarmTonAktiv
        try {
            settingsManager.isProMode = true
            settingsManager.alarmierungAktiv = true
            settingsManager.alarmTonAktiv = true

            composeRule.setContent { SettingsScreen(onBack = {}) }
            composeRule.waitForIdle()
            val title = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_alerting_title)
            composeRule.onNodeWithText(title, substring = true).performScrollTo().performClick()
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("switch_alarmton_aktiv").performScrollTo().assertIsOn()
            composeRule.onNodeWithTag("switch_alarmton_aktiv").performClick()

            assertFalse("Schalter muss alarmTonAktiv tatsaechlich ausschalten", settingsManager.alarmTonAktiv)
            composeRule.onNodeWithTag("switch_alarmton_aktiv").assertIsOff()
        } finally {
            settingsManager.alarmTonAktiv = oldAlarmTon
            settingsManager.alarmierungAktiv = oldAlerting
            settingsManager.isProMode = oldPro
        }
    }

    @Test
    fun systemIntentsFuerAkkuOptimierungUndExakteAlarmeSindImmerGeprueft() {
        assumeTrue("Exakte Alarme gibt es erst ab Android 12", Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)

        Intents.init()
        try {
            intending(anyIntent()).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
            val context = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
            val packageUri = Uri.parse("package:${context.packageName}")

            composeRule.setContent {
                OemDeviceHelperCard(
                    notificationPermissionOverride = true,
                    exactAlarmPermissionOverride = false,
                    batteryOptimizedOverride = true,
                )
            }
            composeRule.waitForIdle()

            composeRule.onNodeWithTag(OEM_BATTERY_OPTIMIZATION_BUTTON_TAG).assertIsDisplayed().performClick()
            intended(
                allOf(
                    hasAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS),
                    hasData(packageUri),
                )
            )

            composeRule.onNodeWithTag(OEM_EXACT_ALARM_BUTTON_TAG).assertIsDisplayed().performClick()
            intended(
                allOf(
                    hasAction(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM),
                    hasData(packageUri),
                )
            )
        } finally {
            Intents.release()
        }
    }

    /**
     * Echtes Geraete-Pendant zu zwei Faellen aus [OemDeviceHelperCardTest] (Robolectric,
     * app/src/test), die die obige [systemIntentsFuerAkkuOptimierungUndExakteAlarmeSindImmerGeprueft]
     * noch nicht deckte - Teil der Bestandsaufnahme nach dem Datumsbereich-Dialog-Bug (Owner-
     * Auftrag 15.09.2026). Der Huawei/EMUI-SecurityException-Fallback bleibt bewusst Robolectric-
     * only: `OemDeviceHelperCard` hat dafuer keinen Test-Override, das echte `Build.MANUFACTURER`
     * eines CI-Emulators ist nie "HUAWEI" und laesst sich auf echter Hardware nicht faelschen.
     */
    @Test
    fun systemIntentFuerBenachrichtigungenErlaubenWirdGeprueft() {
        Intents.init()
        try {
            intending(anyIntent()).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
            val context = ApplicationProvider.getApplicationContext<LaermprotokollApp>()

            composeRule.setContent {
                OemDeviceHelperCard(
                    notificationPermissionOverride = false,
                    exactAlarmPermissionOverride = true,
                    batteryOptimizedOverride = false,
                )
            }
            composeRule.waitForIdle()

            composeRule.onNodeWithTag(OEM_NOTIFICATION_SETTINGS_BUTTON_TAG).assertIsDisplayed().performClick()
            intended(
                allOf(
                    hasAction(Settings.ACTION_APP_NOTIFICATION_SETTINGS),
                    hasExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            )
        } finally {
            Intents.release()
        }
    }

    @Test
    fun optimalerZustandZeigtKeineAktionsButtonsUndDenOptimalBadge() {
        composeRule.setContent {
            OemDeviceHelperCard(
                notificationPermissionOverride = true,
                exactAlarmPermissionOverride = true,
                batteryOptimizedOverride = false,
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Optimal konfiguriert").assertIsDisplayed()
        composeRule.onNodeWithTag(OEM_BATTERY_OPTIMIZATION_BUTTON_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(OEM_NOTIFICATION_SETTINGS_BUTTON_TAG).assertDoesNotExist()
    }

    @Test
    fun testAlarmAusloesenUndStoppenBehandeltZustandOhneAbsturz() {
        val settingsManager = app.container.settingsManager
        val initialAlarmAktiv = settingsManager.alarmierungAktiv
        val initialAlarmTonAktiv = settingsManager.alarmTonAktiv
        settingsManager.alarmierungAktiv = true
        settingsManager.alarmTonAktiv = true

        try {
            composeRule.setContent { SettingsScreen(onBack = {}) }
            composeRule.waitForIdle()

            val secAlarm = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_alerting_title)
            composeRule.onNodeWithText(secAlarm, substring = true).performScrollTo().performClick()
            composeRule.waitForIdle()

            composeRule.onNodeWithText("Test-Alarm").performScrollTo().assertIsDisplayed().performClick()
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                composeRule.onAllNodesWithText("Test-Alarm ausgelöst", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            // Layout-Umbau (12.09.2026): der neue Seiten-Umschalter oben in SettingsScreen
            // verschiebt den gesamten Inhalt um ein Stueck nach unten - ohne erneutes
            // performScrollTo() kann der Ergebnistext zwar (fuer waitUntil) im Semantics-Baum
            // existieren, aber knapp ausserhalb des sichtbaren Viewports liegen.
            composeRule.onNodeWithText("Test-Alarm ausgelöst", substring = true).performScrollTo().assertIsDisplayed()

            composeRule.onNodeWithText("Alarm stoppen").performScrollTo().assertIsDisplayed().performClick()
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                composeRule.onAllNodesWithText("Alarmton gestoppt", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("Alarmton gestoppt", substring = true).performScrollTo().assertIsDisplayed()
        } finally {
            settingsManager.alarmTonAktiv = initialAlarmTonAktiv
            settingsManager.alarmierungAktiv = initialAlarmAktiv
            LocalNotificationAlertChannel.stoppeAlarmTon(app)
        }
    }

    @Test
    fun lokaleTestMeldungOhnePostNotificationsCrashtNicht() {
        assumeTrue(
            "POST_NOTIFICATIONS existiert erst ab Android 13",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
        )

        val context = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        val channel = LocalNotificationAlertChannel(
            context = context,
            settings = context.container.settingsManager,
            notificationPermissionOverride = false,
        )

        assertFalse("Kanal muss ohne POST_NOTIFICATIONS als nicht verfügbar gelten", channel.isAvailable)

        val result = runBlocking {
            channel.send(
                Alert(
                    alertId = 0,
                    kind = AlertKind.TEST,
                    reason = AlertReason.DISCONNECTED,
                    since = Instant.now(),
                    message = "Test-Meldung ohne Benachrichtigungsberechtigung",
                )
            )
        }

        assertTrue(
            "Fehlende Benachrichtigungsberechtigung darf Ton/Vibration des lokalen Alarmwegs nicht abbrechen",
            result.isSuccess,
        )
        LocalNotificationAlertChannel.stoppeAlarmTon(context)
    }

    /**
     * Checkliste Button/Screen-Coverage Phase 6.2, naechste Stufe nach PR #162: der
     * Tab-Umschalter (Owner-Vorgabe 12.09.2026, drei inhaltlich getrennte Einstellungsseiten)
     * wurde bislang nur ueber `initialTab` in anderen Tests indirekt gesetzt, nie tatsaechlich
     * angeklickt. Nutzt jeweils einen hartkodierten Kotlin-Literal-Sektionstitel ("Speicherplatz",
     * "Fotodokumentation") als Nachweis, dass die richtige Seite tatsaechlich sichtbar wird -
     * beide sind bereits als locale-unabhaengig bekannt (keine stringResource-Herkunft).
     */
    @Test
    fun tabUmschalterWechseltZwischenDenDreiEinstellungsseiten() {
        composeRule.setContent { SettingsScreen(onBack = {}) }
        composeRule.waitForIdle()

        val startTitel = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_section_thresholds)
        composeRule.onNodeWithText(startTitel, substring = true).assertIsDisplayed()
        composeRule.onAllNodesWithText("Speicherplatz", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("Fotodokumentation", substring = true).assertCountEquals(0)

        composeRule.onNodeWithTag("settings_tab_daten").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText(startTitel, substring = true).assertCountEquals(0)
        composeRule.onNodeWithText("Speicherplatz", substring = true).assertIsDisplayed()

        composeRule.onNodeWithTag("settings_tab_bericht").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Speicherplatz", substring = true).assertCountEquals(0)
        composeRule.onNodeWithText("Fotodokumentation", substring = true).assertIsDisplayed()

        composeRule.onNodeWithTag("settings_tab_start").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(startTitel, substring = true).assertIsDisplayed()
        composeRule.onAllNodesWithText("Fotodokumentation", substring = true).assertCountEquals(0)
    }

    /**
     * Checkliste Button/Screen-Coverage Phase 6.2: "Grenzwerte nach Wohnraum" oeffnet an zwei
     * Stellen in SettingsScreen.kt denselben [RuhezeitPresetsDialog] (dessen eigener Inhalt schon
     * in eigenen Tests geprueft ist) - bislang wurde aber keiner der beiden Einstiege tatsaechlich
     * angeklickt. Jede Sektion wird nach ihrer Pruefung wieder eingeklappt, damit der
     * Button-Text nicht doppelt im Baum steht, wenn der zweite Einstieg geprueft wird.
     */
    @Test
    fun grenzwerteNachWohnraumDialogOeffnetSichVonBeidenEinstiegen() {
        val settingsManager = app.container.settingsManager
        val oldQuietHours = settingsManager.quietHoursEnabled
        try {
            settingsManager.quietHoursEnabled = true

            composeRule.setContent { SettingsScreen(onBack = {}) }
            composeRule.waitForIdle()

            val wohnraumButtonText = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_ta_laerm_presets)
            val schliessenText = "Schließen"

            // Einstieg 1: Sektion "Schwellenwerte & Audio"
            val aufnahmeTitel = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_section_thresholds)
            composeRule.onNodeWithText(aufnahmeTitel, substring = true).performScrollTo().performClick()
            composeRule.waitForIdle()

            composeRule.onNodeWithText(wohnraumButtonText).performScrollTo().performClick()
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                composeRule.onAllNodesWithTag(RUHEZEIT_PRESETS_LAZY_COLUMN_TAG).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag(RUHEZEIT_PRESETS_LAZY_COLUMN_TAG).assertIsDisplayed()
            composeRule.onNodeWithText(schliessenText).performClick()
            composeRule.waitForIdle()

            composeRule.onNodeWithText(aufnahmeTitel, substring = true).performScrollTo().performClick()
            composeRule.waitForIdle()

            // Einstieg 2: Sektion "Ruhezeiten berücksichtigen"
            val ruhezeitenTitel = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_quiet_hours_title)
            composeRule.onNodeWithText(ruhezeitenTitel, substring = true).performScrollTo().performClick()
            composeRule.waitForIdle()

            composeRule.onNodeWithText(wohnraumButtonText).performScrollTo().performClick()
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                composeRule.onAllNodesWithTag(RUHEZEIT_PRESETS_LAZY_COLUMN_TAG).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag(RUHEZEIT_PRESETS_LAZY_COLUMN_TAG).assertIsDisplayed()
        } finally {
            settingsManager.quietHoursEnabled = oldQuietHours
        }
    }

    /**
     * Checkliste Button/Screen-Coverage Phase 6.2: der "Zur Messgeraet-Kopplung"-Button war
     * bislang nie geklickt worden, nur seine Sichtbarkeit gepruft.
     */
    @Test
    fun btnOpenMeterRuftOnNavigateToMeterAuf() {
        var geklickt = false
        composeRule.setContent { SettingsScreen(onBack = {}, onNavigateToMeter = { geklickt = true }) }
        composeRule.waitForIdle()

        val titel = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_section_thresholds)
        composeRule.onNodeWithText(titel, substring = true).performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("btn_open_meter").performScrollTo().performClick()

        assertTrue("Klick auf btn_open_meter muss onNavigateToMeter aufrufen", geklickt)
    }

    /**
     * Checkliste Button/Screen-Coverage Phase 6.2: der "Zum Papierkorb"-Button war bislang nie
     * geklickt worden. Liegt in der "Speicherplatz"-Sektion, deren Belegungsanzeige asynchron ueber
     * ein LaunchedEffect nachgeladen wird (PR #150-Historie) - deshalb per waitUntil() auf das
     * Erscheinen des Buttons selbst warten statt nur auf waitForIdle() zu vertrauen.
     */
    @Test
    fun btnOpenTrashRuftOnNavigateToTrashAuf() {
        var geklickt = false
        composeRule.setContent {
            SettingsScreen(onBack = {}, initialTab = SettingsTab.DATEN, onNavigateToTrash = { geklickt = true })
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Speicherplatz", substring = true).performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onAllNodesWithTag("btn_open_trash").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("btn_open_trash").performScrollTo().performClick()

        assertTrue("Klick auf btn_open_trash muss onNavigateToTrash aufrufen", geklickt)
    }

    /**
     * Checkliste Button/Screen-Coverage Phase 6.2: der Berichtsangaben-Abfrage-Schalter
     * (BERICHT-Tab) war bislang nie tatsaechlich geklickt worden.
     */
    @Test
    fun stammdatenAbfrageAktivSchalterAendertEinstellung() {
        val settingsManager = app.container.settingsManager
        val oldValue = settingsManager.stammdatenAbfrageAktiv
        try {
            settingsManager.stammdatenAbfrageAktiv = true

            composeRule.setContent { SettingsScreen(onBack = {}, initialTab = SettingsTab.BERICHT) }
            composeRule.waitForIdle()

            composeRule.onNodeWithText("Berichtsangaben", substring = true).performScrollTo().performClick()
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("switch_stammdaten_abfrage_aktiv").performScrollTo().assertIsOn()
            composeRule.onNodeWithTag("switch_stammdaten_abfrage_aktiv").performClick()
            composeRule.waitForIdle()

            // CI-Fehler (zweimal reproduziert, kein Flake): unmittelbar nach performClick() war
            // settingsManager.stammdatenAbfrageAktiv noch nicht auf false umgesprungen, obwohl
            // exakt dasselbe Pruefmuster bei allen anderen Schaltern dieser Klasse (recordWavAudio,
            // alarmierungAktiv, alarmTonAktiv, quietHoursEnabled) anstandslos funktioniert hat.
            // Statt eines sofortigen Reads per waitUntil() pollen und im Fehlerfall den
            // Semantics-Baum dumpen (gleiches Diagnosewerkzeug wie bei der Speicherplatz-Sektion,
            // PR #150), damit ein erneuter CI-Fehlschlag echte Evidenz statt eines weiteren Ratens
            // liefert.
            val ausgeschaltet = runCatching {
                composeRule.waitUntil(timeoutMillis = 5_000L) { !settingsManager.stammdatenAbfrageAktiv }
            }.isSuccess
            if (!ausgeschaltet) {
                schreibeSemantikDiagnose("StammdatenAbfrageSchalter")
            }
            assertTrue("Schalter muss stammdatenAbfrageAktiv tatsaechlich ausschalten", ausgeschaltet)
            composeRule.onNodeWithTag("switch_stammdaten_abfrage_aktiv").assertIsOff()
        } finally {
            settingsManager.stammdatenAbfrageAktiv = oldValue
        }
    }
}
