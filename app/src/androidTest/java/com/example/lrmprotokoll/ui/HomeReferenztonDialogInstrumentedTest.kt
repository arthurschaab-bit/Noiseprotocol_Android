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
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checkliste Button/Screen-Coverage Phase 7e/9b (docs Plan sorted-orbiting-crown.md). Der
 * showReferenceDialog-AlertDialog ("Geraeusch lernen") war laut Audit zu 0% abgedeckt -
 * NoiseRecordItem.onLearn selbst hat zwar schon einen Test
 * (HomeScreenInstrumentedTest.noiseRecordItemZeigtAlleDetailsUndReagiertAufAlleAktionen), aber der
 * prueft nur, dass der Callback feuert, nicht was der Dialog selbst tut.
 *
 * Der "Speichern"-Pfad ruft NoiseClassifier.classifyDetailed() auf - eine ECHTE YAMNet-Inferenz
 * auf einer echten Audiodatei. Phase 7e deckte zunaechst nur das ab, was ohne Klassifizierung
 * pruefbar war (Dialog-Oeffnen, Speichern-Button-Zustand, Abbrechen). Phase 9b (Owner-Entscheidung
 * 16.09.2026: echte Audio-Fixture + echte Inferenz statt eines neuen Klassifikator-Seams) schliesst
 * jetzt den vollen Erfolgspfad: [schreibeTestWav] erzeugt eine echte, minimale WAV-Datei
 * (Sinuswelle, mono, 16-Bit PCM - exakt das Format aus [com.example.lrmprotokoll.audio.AudioRecordingService.writeWavHeader]),
 * gegen die der echte [com.example.lrmprotokoll.audio.NoiseClassifier] (echtes YAMNet-TFLite-Modell,
 * kein Fake) klassifiziert. Erste Instanz eines Tests in diesem Repo, der eine echte
 * Modell-Inferenz ausloest - deshalb ein grosszuegiges Timeout fuer Modell-Laden + Inferenz.
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
        // CI-Fund M12 Schritt 8 (Test Orchestrator, 18.09.2026): F2s Filter-State
        // (MainActivity.kt) liest seinen Anfangswert aus SettingsManager, nicht aus einem
        // frischen Default - unter dem neu eingefuehrten Test Orchestrator (kein
        // clearPackageData, siehe app/build.gradle.kts) ueberlebt das eine noch von einem
        // fruehen Test gesetzte, einschraenkende Filterkriterium den Prozesswechsel und kann den
        // hier eingefuegten aufnahme-Datensatz aus der Liste herausfiltern. Gleiches Muster wie
        // bereits in HomeScreenInstrumentedTest.setUp()/tearDown().
        app.container.settingsManager.filterSearchQuery = ""
        app.container.settingsManager.filterDbMin = 0f
        app.container.settingsManager.filterDbMax = 120f
        app.container.settingsManager.filterOnlyMeter = false
        app.container.settingsManager.filterOnlyCalibrated = false
        app.container.settingsManager.filterOnlyFavorites = false
        app.container.settingsManager.filterOnlyQuietHours = false
        runBlocking {
            app.container.database.noiseDao().insert(aufnahme)
        }
    }

    @After
    fun tearDown() {
        app.container.database.clearAllTables()
        app.container.settingsManager.filterSearchQuery = ""
        app.container.settingsManager.filterDbMin = 0f
        app.container.settingsManager.filterDbMax = 120f
        app.container.settingsManager.filterOnlyMeter = false
        app.container.settingsManager.filterOnlyCalibrated = false
        app.container.settingsManager.filterOnlyFavorites = false
        app.container.settingsManager.filterOnlyQuietHours = false
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
        composeRule.warteUndScrolleZu(hasText(lernChip))
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

    @Test
    fun speichernMitEchterAudiodateiSpeichertEinEchtesReferenzmusterInDerDatenbank() {
        // aufnahme aus setUp() wieder entfernen - dieser Test braucht genau einen Datensatz mit
        // einer echten, klassifizierbaren Audiodatei statt des leeren filePath aus setUp().
        app.container.database.clearAllTables()
        val wavDatei = schreibeTestWav()
        val musterName = "Presslufthammer-Test"
        runBlocking {
            app.container.database.noiseDao().insert(
                NoiseRecord(
                    timestamp = System.currentTimeMillis(),
                    amplitude = 1000.0,
                    dbValue = 40.0,
                    filePath = wavDatei.absolutePath,
                    label = "ReferenztonEchtTest",
                )
            )
        }

        setzeInhaltUndOeffneLernDialog()

        composeRule.onNodeWithTag("input_learn_pattern_name").performTextInput(musterName)
        composeRule.onNodeWithTag("btn_learn_pattern_save").performClick()

        // Grosszuegiges Timeout: erste Nutzung von NoiseClassifier in diesem Repo ueberhaupt -
        // laedt beim ersten Zugriff das ~4 MB YAMNet-TFLite-Modell ueber MediaPipe und fuehrt
        // danach eine echte Inferenz aus.
        composeRule.waitUntil(timeoutMillis = 60_000L) {
            app.container.database.noiseDao().getAllReferencesBlocking().any { it.name == musterName }
        }

        // Das obige waitUntil (bzw. das andernfalls hier werfende first{}) IST der eigentliche
        // Beweis: ein ReferenceSound-Datensatz mit diesem Namen existiert nur, wenn
        // classifyDetailed() tatsaechlich != null zurueckgegeben hat (echte Inferenz gelaufen).
        // pattern selbst (kommaseparierte YAMNet-Kategorien) wird bewusst nicht auf einen
        // konkreten Inhalt geprueft - haengt vom Modell/Audioinhalt ab, kein Overfitting auf ein
        // bestimmtes YAMNet-Ergebnis.
        app.container.database.noiseDao().getAllReferencesBlocking().first { it.name == musterName }

        val titel = composeRule.activity.getString(R.string.learn_pattern_title)
        composeRule.onNodeWithText(titel).assertDoesNotExist()

        wavDatei.delete()
    }

    /**
     * Minimale, aber echte WAV-Datei (mono, 16-Bit PCM, 2 Sekunden 440-Hz-Sinuston) - exakt
     * dasselbe Header-Format wie [com.example.lrmprotokoll.audio.AudioRecordingService.writeWavHeader],
     * damit [com.example.lrmprotokoll.audio.NoiseClassifier] sie wie eine echte Aufnahme liest.
     */
    private fun schreibeTestWav(sampleRate: Int = 16_000, sekunden: Double = 2.0): File {
        val anzahlSamples = (sampleRate * sekunden).toInt()
        val amplitude = 8000.0
        val frequenzHz = 440.0
        val pcm = ByteArray(anzahlSamples * 2)
        for (i in 0 until anzahlSamples) {
            val sample = (amplitude * sin(2.0 * Math.PI * frequenzHz * i / sampleRate)).toInt().toShort()
            pcm[i * 2] = (sample.toInt() and 0xFF).toByte()
            pcm[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }

        val datei = File(app.cacheDir, "referenzton_fixture_${System.nanoTime()}.wav")
        FileOutputStream(datei).use { out ->
            out.write(baueWavHeader(sampleRate = sampleRate, dataLength = pcm.size.toLong()))
            out.write(pcm)
        }
        return datei
    }

    private fun baueWavHeader(sampleRate: Int, dataLength: Long): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val totalLength = dataLength + 36
        return ByteBuffer.allocate(44).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            put("RIFF".toByteArray())
            putInt(totalLength.toInt())
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1.toShort())
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort((channels * bitsPerSample / 8).toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray())
            putInt(dataLength.toInt())
        }.array()
    }
}
