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

    @Test
    fun ntfyGeheimnisseWerdenNichtInDieSicherungAufgenommen() {
        // Praeprotokoll-Befund 05 / Korrekturliste C-6: ntfy-Topic, ntfy-Server und die
        // Heartbeat-URL sind die einzige Zugangskontrolle bzw. ein faelschbares Geheimnis (siehe
        // NtfyAlertChannel-KDoc) und liegen deshalb in EncryptedSharedPreferences - eine Aufnahme
        // hier wuerde sie ueber die automatische Drive-Sicherung im Klartext in die Cloud
        // spiegeln und die M6-Verschluesselung gegenstandslos machen.
        val quelle = neueSettings()
        quelle.ntfyServer = "https://ntfy.example.org"
        quelle.ntfyTopic = "geheimes-topic"
        quelle.heartbeatUrl = "https://heartbeat.example.org/ping/abc123"

        val json = buildEinstellungenJson(quelle)

        assertNotEquals(true, json.has("ntfyServer"))
        assertNotEquals(true, json.has("ntfyTopic"))
        assertNotEquals(true, json.has("heartbeatUrl"))
    }

    @Test
    fun wiederherstellungIgnoriertNtfyGeheimnisseAusAelteremSicherungsformat() {
        // Gegenprobe zu wendeEinstellungenAn: eine Sicherung aus der Zeit VOR der Korrektur kann
        // diese Schluessel noch enthalten - sie duerfen beim Einspielen nicht ploetzlich wieder
        // im Klartext-Zielsettings landen.
        val ziel = neueSettings()
        ziel.ntfyTopic = "bereits-konfiguriertes-topic"

        val altesJson = org.json.JSONObject()
        altesJson.put("ntfyServer", "https://alt.example.org")
        altesJson.put("ntfyTopic", "altes-topic-aus-backup")
        altesJson.put("heartbeatUrl", "https://alt.example.org/ping")

        wendeEinstellungenAn(altesJson, ziel)

        assertEquals("bereits-konfiguriertes-topic", ziel.ntfyTopic)
    }
}
