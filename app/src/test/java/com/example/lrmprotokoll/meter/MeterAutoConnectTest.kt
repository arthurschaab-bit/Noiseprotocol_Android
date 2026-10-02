package com.example.lrmprotokoll.meter

import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.data.SettingsManager
import com.example.lrmprotokoll.testhilfen.MessgeraetKopplungAufraeumenRegel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S-3/F-02: Die Automatik verbindet nur, wenn der Nutzer das will UND die Voraussetzungen
 * stimmen. Jede der drei Bedingungen einzeln geprueft - ein stiller Verbindungsaufbau gegen den
 * Willen des Nutzers waere genauso falsch wie ein Versuch ins Leere.
 *
 * Robolectric wegen [SettingsManager] (EncryptedSharedPreferences). Der Supervisor laeuft gegen
 * [FakeMeterTransport]; geprueft wird die Entscheidung, nicht der Verbindungsaufbau - den decken
 * die Tests in `ConnectionSupervisorTest` ab.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeterAutoConnectTest {
    /**
     * Review-Befund 01.10.2026: das @After unten leert meterDeviceAddress, aber nicht
     * meterDeviceName. Fuer den Auto-Connect-Pfad ist das harmlos - ohne Adresse startet
     * keine Verbindung -, vollstaendige Einstellungs-Isolation ist es nicht.
     */
    @get:Rule
    val kopplungAufraeumen = MessgeraetKopplungAufraeumenRegel()

    private val job = Job()
    private lateinit var settings: SettingsManager
    private lateinit var supervisor: ConnectionSupervisor

    @Before
    fun aufbauen() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        settings = SettingsManager(context)
        settings.meterAutoConnect = true
        settings.meterDeviceAddress = "AA:BB:CC:DD:EE:FF"
        settings.meterDeviceName = "PCE-323"
        supervisor =
            ConnectionSupervisor(
                transport = FakeMeterTransport(scope = CoroutineScope(job)),
                scope = CoroutineScope(job),
            )
    }

    @After
    fun abbauen() {
        job.cancel()
        settings.meterDeviceAddress = null
        settings.meterAutoConnect = true
    }

    private fun autoConnect(berechtigt: Boolean = true) =
        MeterAutoConnect(settings, supervisor, hatVerbindungsberechtigung = { berechtigt })

    @Test
    fun verbindetWennSchalterAnGeraetGepinntUndBerechtigt() {
        assertTrue(autoConnect().verbindeWennGewuenscht())
    }

    @Test
    fun verbindetNichtWennDerSchalterAusIst() {
        settings.meterAutoConnect = false

        assertFalse(
            "Ein ausgeschalteter Automatik-Schalter muss den Verbindungsaufbau verhindern",
            autoConnect().verbindeWennGewuenscht(),
        )
    }

    @Test
    fun verbindetNichtOhneGepinntesGeraet() {
        settings.meterDeviceAddress = null

        assertFalse(autoConnect().verbindeWennGewuenscht())
    }

    @Test
    fun verbindetNichtOhneBluetoothBerechtigung() {
        assertFalse(autoConnect(berechtigt = false).verbindeWennGewuenscht())
    }

    /**
     * Der ausdrueckliche "Verbinden"-Knopf auf dem Messgeraet-Screen fragt den Schalter nicht -
     * wer tippt, will verbinden.
     */
    @Test
    fun verbindeJetztIgnoriertDenSchalterAberNichtDieVoraussetzungen() {
        settings.meterAutoConnect = false
        assertTrue(autoConnect().verbindeJetzt())

        settings.meterDeviceAddress = null
        assertFalse(autoConnect().verbindeJetzt())
    }

    @Test
    fun verbindeWennGewuenschtFunktioniertAuchBeiInaktivemDienst() {
        // Befund 6 / Abschnitt 4: MeterAutoConnect haengt bewusst am AppContainer
        // und verbindet bei App-Start / Foregrounding auch dann, wenn AudioRecordingService
        // noch nicht gestartet wurde.
        assertTrue(autoConnect().verbindeWennGewuenscht())
    }
}
