package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.AppContainer
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.data.NoiseRecord
import com.example.lrmprotokoll.meter.FakeMeterTransport
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checkliste Button/Screen-Coverage Phase 7e (docs Plan sorted-orbiting-crown.md), letzte der
 * fuenf Home-Screen-Einzel-PRs. Der showReferenceDialog-AlertDialog ("Geraeusch lernen") war laut
 * Audit zu 0% abgedeckt - NoiseRecordItem.onLearn selbst hat zwar schon einen Test
 * (HomeScreenInstrumentedTest.noiseRecordItemZeigtAlleDetailsUndReagiertAufAlleAktionen), aber der
 * prueft nur, dass der Callback feuert, nicht was der Dialog selbst tut.
 *
 * Der "Speichern"-Pfad ruft NoiseClassifier.classifyDetailed() auf - eine ECHTE YAMNet-Inferenz
 * auf einer echten Audiodatei. Es gibt in diesem Repo bisher keine gebuendelte Test-WAV-Datei und
 * keinen DI-Seam fuer den Classifier in NoiseProtocolApp (anders als z.B. meterTransportOverride
 * in AppContainer) - der volle Erfolgspfad ("Muster tatsaechlich gespeichert") braucht daher
 * entweder eine neue Testaudio-Fixture plus echte Modell-Inferenz im Instrumented-Test (neuer,
 * schwererer Testtyp) oder einen neuen Produktivcode-Seam. Beides ist eine Owner-Entscheidung
 * (vgl. AGENTS.md 8a, analog zum bereits zurückgestellten "Exakte Alarme erlauben"-Button) und
 * nicht Teil dieses PRs. Abgedeckt wird hier alles, was ohne Klassifizierung echt pruefbar ist:
 * Dialog-Oeffnen ueber die echte NoiseRecordItem-Chip, Speichern-Button-Zustand abhaengig vom
 * Namensfeld, und dass Abbrechen garantiert nichts speichert.
 */
@RunWith(AndroidJUnit4::class)
class HomeReferenztonDialogInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: LaermprotokollApp

    private val aufnahme = NoiseRecord(
        timestamp = System.currentTimeMillis(),
        amplitude = 1000.0,
        dbValue = 40.0,
        filePath = "",
        label = "ReferenztonTest",
    )

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.setCustomContainer(AppContainer(app, FakeMeterTransport()))
        app.container.database.clearAllTables()
        runBlocking {
            app.container.database.noiseDao().insert(aufnahme)
        }
    }

    @After
    fun tearDown() {
        app.container.database.clearAllTables()
        app.resetContainer()
    }

    private fun setzeInhaltUndOeffneLernDialog() {
        composeRule.setContent {
            NoiseProtocolApp(
                onNavigateToPlayer = {},
                onNavigateToSettings = {},
                onNavigateToMeter = {},
                onNavigateToProtokoll = {},
                onNavigateToDiagnose = {},
                onNavigateToVideo = {},
            )
        }
        composeRule.waitForIdle()

        val lernChip = composeRule.activity.getString(R.string.action_learn_pattern)
        composeRule.onNodeWithTag("home_lazy_column").performScrollToNode(hasText(lernChip))
        composeRule.onNodeWithText(lernChip).performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun speichernButtonIstNurBeiAusgefuelltemNamenAktiv() {
        setzeInhaltUndOeffneLernDialog()

        composeRule.onNodeWithTag("btn_learn_pattern_save").assertIsNotEnabled()

        composeRule.onNodeWithTag("input_learn_pattern_name").performTextInput("Kompressor")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("btn_learn_pattern_save").assertIsEnabled()

        composeRule.onNodeWithTag("input_learn_pattern_name").performTextClearance()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("btn_learn_pattern_save").assertIsNotEnabled()
    }

    @Test
    fun abbrechenSchliesstDenDialogOhneEinMusterZuSpeichern() {
        setzeInhaltUndOeffneLernDialog()

        val titel = composeRule.activity.getString(R.string.learn_pattern_title)
        composeRule.onNodeWithText(titel).assertIsDisplayed()

        composeRule.onNodeWithTag("input_learn_pattern_name").performTextInput("Kompressor")
        composeRule.onNodeWithTag("btn_learn_pattern_cancel").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(titel).assertDoesNotExist()
        // Kein Muster gelernt -> die Referenzgeraeusch-Sektion existiert erst gar nicht
        // (references.isEmpty() blendet den ganzen Abschnitt aus).
        composeRule.onAllNodesWithText(
            composeRule.activity.getString(R.string.learned_patterns_count, 1)
        ).assertCountEquals(0)
    }
}
