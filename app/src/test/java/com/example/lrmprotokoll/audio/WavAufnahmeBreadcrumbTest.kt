package com.example.lrmprotokoll.audio

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.AppContainer
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.data.AppDatabase
import com.example.lrmprotokoll.diagnose.DiagnosticCode
import com.example.lrmprotokoll.messreihe.MeterTriggerSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WavAufnahmeBreadcrumbTest {

    @After
    fun tearDown() {
        AppDatabase.resetInstance()
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        app.container = AppContainer(app)
    }

    @Test
    fun vollstaendigeAufnahmeErzeugtGenauEinenBreadcrumbMitAllenFeldern() {
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
            app.container.settingsManager.meterDeviceAddress = null
            app.container.settingsManager.recordDurationSeconds = 0
            app.container.settingsManager.aiMode = "OFFLINE"

            val intent = Intent(app, AudioRecordingService::class.java)
            val controller = Robolectric.buildService(AudioRecordingService::class.java, intent)
            controller.create()
            val service = controller.get()

            val auswertung = MeterTriggerSource.Auswertung(
                ausgeloest = true,
                pegel = 62.5,
                calibratedDbA = 62.5,
                meterWeighting = "A",
                meterConnected = true,
            )

            // Menge statt Anzahl: Die Historie haelt nur die letzten 100 Eintraege - ist sie voll, bleibt
            // die Anzahl gleich und drop(vorher) wuerde die neuen Eintraege verwerfen.
            val breadcrumbsVorher = app.container.diagnosticsReporter.recentBreadcrumbs(200).toSet()

            service.starteWavAufnahme(
                initialAmplitude = 1500.0,
                dbValue = 62.5,
                auswertung = auswertung,
                isQuiet = false,
            )

            val neueBreadcrumbs = app.container.diagnosticsReporter
                .recentBreadcrumbs(200)
                .filter { it !in breadcrumbsVorher }
                .filter { it.category == "AudioService" }

            // Akzeptanzkriterium 1: Genau EIN Breadcrumb je Aufnahme
            assertEquals(
                "Es darf genau ein AudioService-Breadcrumb erzeugt werden (vorher: 3)",
                1,
                neueBreadcrumbs.size,
            )

            val bc = neueBreadcrumbs.first()
            assertEquals("WAV-Aufnahme gespeichert", bc.message)

            val data = bc.data
            // Vereinigung aller Felder aus WAV-Aufnahme gestartet, beendet und NoiseRecord gespeichert
            assertNotNull(data["fileName"])
            assertNotNull(data["path"])
            assertNotNull(data["sampleRate"])
            assertNotNull(data["zielDauerMs"])
            assertNotNull(data["dauerMs"])
            assertNotNull(data["preRollBytes"])
            assertNotNull(data["dataBytes"])
            assertNotNull(data["fileBytes"])
            assertNotNull(data["unterbrochen"])
            assertNotNull(data["audioIstAktiv"])
            assertEquals(true, data["meterConnected"])
            assertEquals(62.5, data["pegelDb"])
            assertNotNull(data["recordId"])
            assertEquals(true, data["gespeichert"])

            controller.destroy()
        }
    }

    @Test
    fun fehlerpfadOhneNoiseRecordErzeugtGenauEinenBreadcrumbMitFehlerkennzeichen() {
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
            app.container.settingsManager.meterDeviceAddress = null
            app.container.settingsManager.recordDurationSeconds = 0
            app.container.settingsManager.aiMode = "OFFLINE"

            val intent = Intent(app, AudioRecordingService::class.java)
            val controller = Robolectric.buildService(AudioRecordingService::class.java, intent)
            controller.create()
            val service = controller.get()

            // Schließe die Datenbank, damit Room beim insert fehlschlägt
            app.container.database.close()

            val auswertung = MeterTriggerSource.Auswertung(
                ausgeloest = true,
                pegel = 58.0,
                calibratedDbA = null,
                meterWeighting = null,
                meterConnected = false,
            )

            // Menge statt Anzahl: Die Historie haelt nur die letzten 100 Eintraege - ist sie voll, bleibt
            // die Anzahl gleich und drop(vorher) wuerde die neuen Eintraege verwerfen.
            val breadcrumbsVorher = app.container.diagnosticsReporter.recentBreadcrumbs(200).toSet()

            service.starteWavAufnahme(
                initialAmplitude = 1200.0,
                dbValue = 58.0,
                auswertung = auswertung,
                isQuiet = true,
            )

            val neueBreadcrumbs = app.container.diagnosticsReporter
                .recentBreadcrumbs(200)
                .filter { it !in breadcrumbsVorher }
                .filter { it.category == "AudioService" }

            // Akzeptanzkriterium 2: Fehlerpfad ohne NoiseRecord erzeugt genau einen Breadcrumb mit Fehlerkennzeichen
            assertEquals(
                "Fehlerpfad darf genau einen AudioService-Breadcrumb erzeugen",
                1,
                neueBreadcrumbs.size,
            )

            val bc = neueBreadcrumbs.first()
            assertEquals("WAV-Aufnahme nicht gespeichert", bc.message)
            assertEquals(false, bc.data["gespeichert"])
            assertNull(bc.data["recordId"])
            assertNotNull(bc.data["fehler"])

            controller.destroy()
        }
    }

    @Test
    fun unterbrocheneAufnahmeMeldetAudioWavInterruptedUndSetztUnterbrochenImBreadcrumb() {
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
            app.container.settingsManager.meterDeviceAddress = null
            // Ziel-Dauer 10 Sekunden, aber Dienst-Aufnahme inaktiv -> Schleife bricht sofort ab (< 10s)
            app.container.settingsManager.recordDurationSeconds = 10
            app.container.settingsManager.aiMode = "OFFLINE"

            val intent = Intent(app, AudioRecordingService::class.java)
            val controller = Robolectric.buildService(AudioRecordingService::class.java, intent)
            controller.create()
            val service = controller.get()

            val auswertung = MeterTriggerSource.Auswertung(
                ausgeloest = true,
                pegel = 70.0,
                calibratedDbA = 70.0,
                meterWeighting = "A",
                meterConnected = true,
            )

            service.starteWavAufnahme(
                initialAmplitude = 2000.0,
                dbValue = 70.0,
                auswertung = auswertung,
                isQuiet = false,
            )

            val events = app.container.diagnosticsReporter.recentEvents()
            assertTrue(
                "AUDIO_WAV_INTERRUPTED muss gemeldet werden",
                events.any { it.code == DiagnosticCode.AUDIO_WAV_INTERRUPTED },
            )

            val bc = app.container.diagnosticsReporter.recentBreadcrumbs()
                .first { it.category == "AudioService" && it.message == "WAV-Aufnahme gespeichert" }
            assertEquals(true, bc.data["unterbrochen"])

            controller.destroy()
        }
    }
}
