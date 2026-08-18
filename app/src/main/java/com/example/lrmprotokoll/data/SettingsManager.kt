package com.example.lrmprotokoll.data

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("noise_settings", Context.MODE_PRIVATE)

    var dbThreshold: Float
        get() = prefs.getFloat("db_threshold", 60.0f)
        set(value) = prefs.edit().putFloat("db_threshold", value).apply()

    var preRollSeconds: Int
        get() = prefs.getInt("pre_roll", 2)
        set(value) = prefs.edit().putInt("pre_roll", value).apply()

    var recordDurationSeconds: Int
        get() = prefs.getInt("duration", 3)
        set(value) = prefs.edit().putInt("duration", value).apply()

    var aiEnabled: Boolean
        get() = prefs.getBoolean("ai_enabled", true)
        set(value) = prefs.edit().putBoolean("ai_enabled", value).apply()

    var aiConfidenceThreshold: Float
        get() = prefs.getFloat("ai_confidence", 0.3f)
        set(value) = prefs.edit().putFloat("ai_confidence", value).apply()

    var audioSampleRate: Int
        get() = prefs.getInt("sample_rate", 16000)
        set(value) = prefs.edit().putInt("sample_rate", value).apply()

    // Geraete-Pinning fuer das PCE-323 (Plan Abschnitt 6): nach der Erstkopplung wird
    // ausschliesslich noch zu dieser Adresse verbunden.
    var meterDeviceAddress: String?
        get() = prefs.getString("meter_device_address", null)
        set(value) = prefs.edit().putString("meter_device_address", value).apply()

    var meterDeviceName: String?
        get() = prefs.getString("meter_device_name", null)
        set(value) = prefs.edit().putString("meter_device_name", value).apply()

    // Fuer die automatische Wiederaufnahme nach einem Geraeteneustart (Plan Abschnitt 5.4):
    // monitoringWasActive haelt fest, ob der Foreground Service beim letzten expliziten Stop
    // noch lief (egal ob wegen Audio- oder Messgeraet-Ueberwachung), audioMonitoringWasActive
    // zusaetzlich, ob davon auch die Mikrofon-Schwellwertueberwachung betroffen war - ein
    // Neustart soll nur das reaktivieren, was der Nutzer tatsaechlich zuletzt laufen hatte.
    var monitoringWasActive: Boolean
        get() = prefs.getBoolean("monitoring_was_active", false)
        set(value) = prefs.edit().putBoolean("monitoring_was_active", value).apply()

    var audioMonitoringWasActive: Boolean
        get() = prefs.getBoolean("audio_monitoring_was_active", false)
        set(value) = prefs.edit().putBoolean("audio_monitoring_was_active", value).apply()

    // ---------------------------------------------------------------- M5: Alarmierung

    var alarmierungAktiv: Boolean
        get() = prefs.getBoolean("alarm_enabled", false)
        set(value) = prefs.edit().putBoolean("alarm_enabled", value).apply()

    /** Karenzzeit in Sekunden. Beschlossener Default 60 s, einstellbar 10 s bis 15 min (Plan 7.2). */
    var karenzzeitSekunden: Int
        get() = prefs.getInt("alarm_grace_seconds", 60).coerceIn(10, 15 * 60)
        set(value) = prefs.edit().putInt("alarm_grace_seconds", value.coerceIn(10, 15 * 60)).apply()

    var ntfyAktiv: Boolean
        get() = prefs.getBoolean("ntfy_enabled", false)
        set(value) = prefs.edit().putBoolean("ntfy_enabled", value).apply()

    /**
     * Basis-URL des ntfy-Servers.
     *
     * Bewusst eine Einstellung und keine Konstante im Code: Die Entscheidung des Owners lautete
     * "ntfy ausprobieren", nicht "fuer immer ntfy.sh". Ein Wechsel auf eine selbst betriebene
     * Instanz - der empfohlene Weg, sobald es ernst wird - soll eine Einstellungsaenderung
     * bleiben und kein Umbau.
     */
    var ntfyServer: String
        get() = prefs.getString("ntfy_server", "https://ntfy.sh").orEmpty().ifBlank { "https://ntfy.sh" }
        set(value) = prefs.edit().putString("ntfy_server", value.trim().trimEnd('/')).apply()

    /**
     * Das ntfy-Topic. Beim oeffentlichen Server ist der Topic-Name die EINZIGE Zugangskontrolle -
     * wer ihn kennt, liest mit und kann senden. Er wird deshalb zufaellig erzeugt
     * ([erzeugeNtfyTopic]) und darf nirgends protokolliert werden.
     *
     * Die verschluesselte Ablage ist M6 (Keystore, verschluesselter DataStore) und bewusst noch
     * nicht hier: eine selbstgebaute Teilloesung waere schlechter als die dokumentierte Luecke.
     */
    var ntfyTopic: String
        get() = prefs.getString("ntfy_topic", "").orEmpty()
        set(value) = prefs.edit().putString("ntfy_topic", value).apply()

    /**
     * Ping-URL der Totmannschaltung (healthchecks.io oder selbst betrieben). Leer heisst aus -
     * niemand wird zu einem Fremddienst gezwungen.
     */
    var heartbeatUrl: String
        get() = prefs.getString("heartbeat_url", "").orEmpty()
        set(value) = prefs.edit().putString("heartbeat_url", value.trim()).apply()

    var entwarnungUeberNtfy: Boolean
        get() = prefs.getBoolean("entwarnung_ntfy", true)
        set(value) = prefs.edit().putBoolean("entwarnung_ntfy", value).apply()

    var entwarnungUeberMeldung: Boolean
        get() = prefs.getBoolean("entwarnung_local", true)
        set(value) = prefs.edit().putBoolean("entwarnung_local", value).apply()
}

/**
 * Erzeugt ein Topic aus [SecureRandom]. 32 Zeichen aus 62 moeglichen sind rund 190 Bit - beim
 * oeffentlichen ntfy-Server ist das die gesamte Sicherheit der Alarmierung, ein sprechender oder
 * kurzer Name waere hier fahrlaessig.
 */
fun erzeugeNtfyTopic(zufall: java.security.SecureRandom = java.security.SecureRandom()): String {
    val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    return (1..32).map { alphabet[zufall.nextInt(alphabet.length)] }.joinToString("")
}
