package com.example.lrmprotokoll.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.anyIntent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.AppContainer
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.backup.SicherungManager
import com.example.lrmprotokoll.data.AppDatabase
import com.example.lrmprotokoll.data.NoiseRecord
import com.example.lrmprotokoll.meter.FakeMeterTransport
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checkliste Button/Screen-Coverage Phase 9c (docs Plan sorted-orbiting-crown.md). Der
 * "Sicherung und Wiederherstellung"-Abschnitt in SettingsScreen war zuvor nur auf reiner
 * Logik-Ebene geprueft (SicherungManagerTest, Robolectric) - kein Test prüfte die tatsächliche
 * Compose-Verkabelung: Buttons -> SAF-Dateiauswahl -> Bestätigungsdialog -> SicherungManager.
 *
 * [SicherungManager.starteNeustart] beendet nach erfolgreicher Wiederherstellung den Prozess
 * hart (`Runtime.getRuntime().exit(0)`) - ein echter Aufruf würde den Instrumentierungsprozess
 * töten, bevor irgendein Testergebnis gemeldet werden könnte (Owner-Rückfrage 17.09.2026: das
 * ist für genau diese eine Zeile nicht nur riskant, sondern technisch nicht automatisierbar,
 * analog zur `pm revoke`-Kill-Warnung in `run-instrumented-tests.sh`, hier aber unconditional
 * statt nur potenziell). [SettingsScreen]s neuer `neustartAusloeser`-Parameter (Default: der
 * echte Aufruf) erlaubt es, hier stattdessen einen Test-Stellvertreter einzusetzen - alles davor
 * läuft echt: SAF-Uri-Auswahl via Espresso-Intents, echter Bestätigungsdialog, echter
 * [SicherungManager.spieleSicherungEin] mit echtem Datei- und Datenbank-Roundtrip.
 */
@RunWith(AndroidJUnit4::class)
class SettingsSicherungInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: LaermprotokollApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.setCustomContainer(AppContainer(app, FakeMeterTransport()))
        app.container.database.clearAllTables()
    }

    @After
    fun tearDown() {
        app.container.database.clearAllTables()
        app.resetContainer()
    }

    private fun oeffneSicherungsAbschnitt(neustartAufgerufen: () -> Unit = {}) {
        composeRule.setContent {
            SettingsScreen(
                onBack = {},
                initialTab = SettingsTab.DATEN,
                neustartAusloeser = { neustartAufgerufen() },
            )
        }
        composeRule.waitForIdle()
        val titel = composeRule.activity.getString(R.string.settings_backup_title)
        composeRule.onNodeWithText(titel, substring = true).performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun sicherungErstellenSchreibtEineEchteZipDateiMitDatenbankinhalt() {
        val zeitstempel = 1_850_000_000_000L + System.nanoTime() % 1_000_000L
        runBlocking {
            AppDatabase.getDatabase(app).noiseDao().insert(
                NoiseRecord(
                    timestamp = zeitstempel,
                    amplitude = 0.0,
                    dbValue = 55.0,
                    filePath = "",
                    label = "SicherungUiTest-Erstellen",
                )
            )
        }

        val zielDatei = File(app.cacheDir, "ui_sicherung_erstellt_${System.nanoTime()}.zip")
        zielDatei.delete()

        Intents.init()
        try {
            intending(anyIntent()).respondWith(
                Instrumentation.ActivityResult(
                    Activity.RESULT_OK,
                    Intent().setData(Uri.fromFile(zielDatei)),
                )
            )

            oeffneSicherungsAbschnitt()
            composeRule.onNodeWithTag("btn_backup_create").performScrollTo().performClick()

            composeRule.waitUntil(timeoutMillis = 10_000L) {
                zielDatei.exists() && zielDatei.length() > 0
            }

            val kopf = zielDatei.readBytes().copyOfRange(0, 2)
            assertTrue("Sicherung muss eine echte ZIP-Datei sein (PK-Kopf)", kopf.contentEquals(byteArrayOf(0x50, 0x4B)))

            val eintraege = mutableSetOf<String>()
            ZipInputStream(zielDatei.inputStream()).use { zis ->
                var eintrag = zis.nextEntry
                while (eintrag != null) {
                    eintraege += eintrag.name
                    eintrag = zis.nextEntry
                }
            }
            assertEquals(setOf("manifest.json", "settings.json", "noise_database"), eintraege)
        } finally {
            Intents.release()
            zielDatei.delete()
        }
    }

    @Test
    fun vollerWiederherstellungsPfadStelltDatenWiederHerUndLoestNeustartAusloeserAusStattDenProzessZuBeenden() {
        val settings = app.container.settingsManager
        val zeitstempel = 1_850_100_000_000L + System.nanoTime() % 1_000_000L
        runBlocking {
            AppDatabase.getDatabase(app).noiseDao().insert(
                NoiseRecord(
                    timestamp = zeitstempel,
                    amplitude = 0.0,
                    dbValue = 62.0,
                    filePath = "",
                    label = "SicherungUiTest-Wiederherstellen",
                )
            )
        }

        val sicherungsDatei = File(app.cacheDir, "ui_sicherung_quelle_${System.nanoTime()}.zip")
        runBlocking {
            val ergebnis = SicherungManager.erstelleSicherung(app, Uri.fromFile(sicherungsDatei), settings)
            assertTrue(ergebnis.nachricht, ergebnis.erfolg)
        }

        // Aktuellen Stand "beschaedigen", um zu belegen, dass die Wiederherstellung tatsaechlich
        // den gesicherten Stand zurueckbringt (gleiches Prinzip wie SicherungManagerTest).
        runBlocking {
            val dao = AppDatabase.getDatabase(app).noiseDao()
            dao.deleteMultiple(dao.getAlleAktiven().filter { it.timestamp == zeitstempel }.map { it.id })
            assertTrue(dao.getAlleAktiven().none { it.timestamp == zeitstempel })
        }

        var neustartAufgerufen = false

        Intents.init()
        try {
            intending(anyIntent()).respondWith(
                Instrumentation.ActivityResult(
                    Activity.RESULT_OK,
                    Intent().setData(Uri.fromFile(sicherungsDatei)),
                )
            )

            oeffneSicherungsAbschnitt(neustartAufgerufen = { neustartAufgerufen = true })
            composeRule.onNodeWithTag("btn_backup_restore").performScrollTo().performClick()
            composeRule.waitForIdle()

            val warnTitel = composeRule.activity.getString(R.string.settings_backup_restore_warning_title)
            composeRule.onNodeWithText(warnTitel).assertIsDisplayed()
            composeRule.onNodeWithTag("btn_backup_restore_confirm").performClick()

            composeRule.waitUntil(timeoutMillis = 10_000L) { neustartAufgerufen }

            runBlocking {
                val wiederhergestellteDao = AppDatabase.getDatabase(app).noiseDao()
                assertTrue(wiederhergestellteDao.getAlleAktiven().any { it.timestamp == zeitstempel })
            }
        } finally {
            Intents.release()
            sicherungsDatei.delete()
        }
    }

    @Test
    fun abbrechenImBestaetigungsdialogFuehrtKeineWiederherstellungDurch() {
        val zeitstempel = 1_850_200_000_000L + System.nanoTime() % 1_000_000L
        runBlocking {
            AppDatabase.getDatabase(app).noiseDao().insert(
                NoiseRecord(
                    timestamp = zeitstempel,
                    amplitude = 0.0,
                    dbValue = 40.0,
                    filePath = "",
                    label = "SicherungUiTest-Abbrechen",
                )
            )
        }

        // Der Inhalt der ausgewaehlten Datei spielt hier keine Rolle - Abbrechen darf
        // SicherungManager.spieleSicherungEin gar nicht erst aufrufen.
        val beliebigeDatei = File.createTempFile("ui_sicherung_abbrechen", ".zip", app.cacheDir)

        var neustartAufgerufen = false

        Intents.init()
        try {
            intending(anyIntent()).respondWith(
                Instrumentation.ActivityResult(
                    Activity.RESULT_OK,
                    Intent().setData(Uri.fromFile(beliebigeDatei)),
                )
            )

            oeffneSicherungsAbschnitt(neustartAufgerufen = { neustartAufgerufen = true })
            composeRule.onNodeWithTag("btn_backup_restore").performScrollTo().performClick()
            composeRule.waitForIdle()

            val warnTitel = composeRule.activity.getString(R.string.settings_backup_restore_warning_title)
            composeRule.onNodeWithText(warnTitel).assertIsDisplayed()
            composeRule.onNodeWithTag("btn_backup_restore_cancel").performClick()
            composeRule.waitForIdle()

            composeRule.onNodeWithText(warnTitel).assertDoesNotExist()
            assertFalse(neustartAufgerufen)

            runBlocking {
                val dao = AppDatabase.getDatabase(app).noiseDao()
                assertTrue(dao.getAlleAktiven().any { it.timestamp == zeitstempel })
            }
        } finally {
            Intents.release()
            beliebigeDatei.delete()
        }
    }
}
