package com.example.lrmprotokoll.backup

import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.data.SettingsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PROMPT_M10_FUNKTIONEN.md F13: [buildEinstellungenJson]/[wendeEinstellungenAn] brauchen ein
 * echtes [SettingsManager] (SharedPreferences), deshalb Robolectric statt reiner JVM-Test - die
 * eigentliche Zuordnungslogik bleibt trotzdem eine reine Funktion ohne Datei-/Zip-I/O.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SicherungEinstellungenTest {

    private fun neueSettings() = SettingsManager(
        ApplicationProvider.getApplicationContext(),
        securePrefs = null,
    )

    @Test
    fun rundtripUebertraegtAlleErfasstenFelderKorrekt() {
        val quelle = neueSettings()
        quelle.dbThreshold = 62.5f
        quelle.recordWavAudio = false
        quelle.aiMode = "ONLINE"
        quelle.alarmierungAktiv = true
        quelle.karenzzeitSekunden = 120
        quelle.ntfyServer = "https://ntfy.example.org"
        quelle.ntfyTopic = "mein-topic"
        quelle.heartbeatUrl = "https://heartbeat.example.org/ping"
        quelle.driveFolderName = "Lärmprotokoll-Sicherung"
        quelle.appLanguage = "en"
        quelle.autoRetentionEnabled = true
        quelle.autoRetentionDays = 45
        quelle.quietHoursEnabled = true
        quelle.quietHoursStartHour = 22
        quelle.quietHoursThreshold = 45.0f

        val json = buildEinstellungenJson(quelle)

        val ziel = neueSettings()
        wendeEinstellungenAn(json, ziel)

        assertEquals(62.5f, ziel.dbThreshold, 0.01f)
        assertEquals(false, ziel.recordWavAudio)
        assertEquals("ONLINE", ziel.aiMode)
        assertEquals(true, ziel.alarmierungAktiv)
        assertEquals(120, ziel.karenzzeitSekunden)
        assertEquals("https://ntfy.example.org", ziel.ntfyServer)
        assertEquals("mein-topic", ziel.ntfyTopic)
        assertEquals("https://heartbeat.example.org/ping", ziel.heartbeatUrl)
        assertEquals("Lärmprotokoll-Sicherung", ziel.driveFolderName)
        assertEquals("en", ziel.appLanguage)
        assertEquals(true, ziel.autoRetentionEnabled)
        assertEquals(45, ziel.autoRetentionDays)
        assertEquals(true, ziel.quietHoursEnabled)
        assertEquals(22, ziel.quietHoursStartHour)
        assertEquals(45.0f, ziel.quietHoursThreshold, 0.01f)
    }

    @Test
    fun fehlendeFelderImJsonUeberschreibenDenZielwertNicht() {
        // Gegenprobe zur has()-Waechter-Logik: ein leeres JSON darf einen bereits gesetzten
        // Zielwert nicht auf einen Default zuruecksetzen.
        val ziel = neueSettings()
        ziel.dbThreshold = 77.0f

        wendeEinstellungenAn(org.json.JSONObject(), ziel)

        assertEquals(77.0f, ziel.dbThreshold, 0.01f)
    }

    @Test
    fun sitzungsdatenWerdenNichtInDieSicherungAufgenommen() {
        // Gegenprobe: BLE-Pairing und Drive-Sitzungszustand duerfen nicht im Sicherungs-JSON
        // landen (siehe Begruendung in SicherungEinstellungen.kt).
        val quelle = neueSettings()
        quelle.meterDeviceAddress = "AA:BB:CC:DD:EE:FF"
        quelle.googleAccountEmail = "nutzer@example.org"

        val json = buildEinstellungenJson(quelle)

        assertNotEquals(true, json.has("meterDeviceAddress"))
        assertNotEquals(true, json.has("googleAccountEmail"))
    }
}
