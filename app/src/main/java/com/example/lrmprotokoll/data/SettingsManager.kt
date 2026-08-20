package com.example.lrmprotokoll.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

private const val TAG = "SettingsManager"

class SettingsManager(
    context: Context,
    /**
     * Verschluesselte Ablage fuer die drei sicherheitsrelevanten Werte aus [prefs] (Plan
     * Abschnitt 6: "Rufnummern und Alarmkonfiguration in EncryptedSharedPreferences ...,
     * Schluessel im Android Keystore"). Rufnummern gibt es seit dem Streichen des SMS-Kanals
     * (Plan 13.4) nicht mehr - was bleibt, sind [ntfyTopic] (die alleinige Zugangskontrolle des
     * Alarmkanals beim oeffentlichen Server), [ntfyServer] und [heartbeatUrl] (deren Kenntnis
     * einen Fremd-Ping faelschen koennte). Alles andere (Schwellwerte, Aggregationsintervalle,
     * ...) bleibt bewusst in [prefs]: Verschluesselung dort waere Mehraufwand ohne
     * Sicherheitsgewinn - kein Angreifer profitiert vom Kenntnis eines dB-Schwellwerts.
     *
     * Als Konstruktorparameter statt eines fest verdrahteten Felds, damit die Migrations-/
     * Fallback-LOGIK in [leseVerschluesselt]/[schreibeVerschluesselt] unabhaengig vom echten
     * Android Keystore testbar ist: Robolectric/die JVM kennen den Provider "AndroidKeyStore"
     * grundsaetzlich nicht (kein Software-Ersatz vorhanden, `KeyStoreException: AndroidKeyStore
     * not found` - selbst gepruefte, nicht nur vermutete Tatsache). Der Default reproduziert das
     * Produktionsverhalten inklusive Fallback; ob EncryptedSharedPreferences auf einem echten
     * Geraet tatsaechlich verschluesselt, ist damit nur am Geraet zu pruefen (siehe
     * docs/PROMPT_M6.md), nicht in diesem Test-Setup.
     */
    private val securePrefs: SharedPreferences? = sicherePraefsOderNull(context),
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("noise_settings", Context.MODE_PRIVATE)

    /**
     * Liest [schluessel] verschluesselt, mit einmaliger Migration aus dem Klartext-Feld in
     * [prefs]: bestehende Installationen sollen ihre ntfy-Konfiguration nicht verlieren, nur weil
     * M6 nachtraeglich verschluesselt. Kann [securePrefs] nicht erzeugt werden (Keystore-Problem
     * auf diesem Geraet), wird auf den Klartextwert zurueckgefallen statt die Einstellung
     * unbenutzbar zu machen - eine dokumentierte Luecke ist besser als eine App, die ihre
     * Alarmkonfiguration verliert.
     */
    private fun leseVerschluesselt(schluessel: String, default: String): String {
        val sicher = securePrefs
        if (sicher == null) return prefs.getString(schluessel, default).orEmpty().ifBlank { default }

        sicher.getString(schluessel, null)?.let { return it }
        val klartext = prefs.getString(schluessel, null)
        if (klartext != null) {
            sicher.edit().putString(schluessel, klartext).apply()
            prefs.edit().remove(schluessel).apply()
            return klartext
        }
        return default
    }

    private fun schreibeVerschluesselt(schluessel: String, wert: String) {
        val sicher = securePrefs
        if (sicher == null) {
            prefs.edit().putString(schluessel, wert).apply()
            return
        }
        sicher.edit().putString(schluessel, wert).apply()
        // Falls noch ein Klartextwert aus der Zeit vor M6 herumliegt (z.B. wenn securePrefs beim
        // Lesen fehlschlug, jetzt aber wieder verfuegbar ist).
        prefs.edit().remove(schluessel).apply()
    }

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
        get() = leseVerschluesselt("ntfy_server", "https://ntfy.sh").ifBlank { "https://ntfy.sh" }
        set(value) = schreibeVerschluesselt("ntfy_server", value.trim().trimEnd('/'))

    /**
     * Das ntfy-Topic. Beim oeffentlichen Server ist der Topic-Name die EINZIGE Zugangskontrolle -
     * wer ihn kennt, liest mit und kann senden. Er wird deshalb zufaellig erzeugt
     * ([erzeugeNtfyTopic]) und darf nirgends protokolliert werden.
     *
     * Verschluesselt abgelegt (M6, Plan Abschnitt 6) - siehe [securePrefs]/[leseVerschluesselt].
     */
    var ntfyTopic: String
        get() = leseVerschluesselt("ntfy_topic", "")
        set(value) = schreibeVerschluesselt("ntfy_topic", value)

    /**
     * Ping-URL der Totmannschaltung (healthchecks.io oder selbst betrieben). Leer heisst aus -
     * niemand wird zu einem Fremddienst gezwungen.
     */
    var heartbeatUrl: String
        get() = leseVerschluesselt("heartbeat_url", "")
        set(value) = schreibeVerschluesselt("heartbeat_url", value.trim())

    var entwarnungUeberNtfy: Boolean
        get() = prefs.getBoolean("entwarnung_ntfy", true)
        set(value) = prefs.edit().putBoolean("entwarnung_ntfy", value).apply()

    var entwarnungUeberMeldung: Boolean
        get() = prefs.getBoolean("entwarnung_local", true)
        set(value) = prefs.edit().putBoolean("entwarnung_local", value).apply()

    // ---------------------------------------------------------------- M7b: Google-Drive-Sync

    var driveSyncEnabled: Boolean
        get() = prefs.getBoolean("drive_sync_enabled", false)
        set(value) = prefs.edit().putBoolean("drive_sync_enabled", value).apply()

    /** `folderId` des von der App angelegten Drive-Ordners (Plan 8.4.3, drive.file-Scope). */
    var driveFolderId: String?
        get() = prefs.getString("drive_folder_id", null)
        set(value) = prefs.edit().putString("drive_folder_id", value).apply()

    var driveFolderName: String
        get() = prefs.getString("drive_folder_name", "Lärmprotokoll").orEmpty().ifBlank { "Lärmprotokoll" }
        set(value) = prefs.edit().putString("drive_folder_name", value).apply()

    /**
     * Aggregationsfenster in Sekunden fuer die Drive-CSV (Plan 8.4.1). Default 1 s: die feinste
     * Aufloesung, bei der eine Fensterbildung (und damit Luecken-Erkennung, Plan 8.4.2) noch
     * sinnvoll ist - Sub-Sekunden-Fenster waeren bei ~2 Hz Messgeraet-Rate ohnehin meist leer.
     * Bewusste Abweichung von der Plan-Empfehlung "10 s" auf ausdruecklichen Owner-Wunsch nach
     * der feinstmoeglichen Aufloesung; dafuer ist [driveWlanOnly] default an.
     */
    var driveAggregationSekunden: Int
        get() = prefs.getInt("drive_aggregation_seconds", 1).coerceAtLeast(1)
        set(value) = prefs.edit().putInt("drive_aggregation_seconds", value.coerceAtLeast(1)).apply()

    /**
     * Default AN: bei feinstmoeglicher Aggregation (1 s) faellt deutlich mehr Uploadvolumen an
     * als bei den im Plan durchgerechneten 10 s. WLAN-only faengt das ab, ohne die Aufloesung
     * einzuschraenken.
     */
    var driveWlanOnly: Boolean
        get() = prefs.getBoolean("drive_wlan_only", true)
        set(value) = prefs.edit().putBoolean("drive_wlan_only", value).apply()

    /**
     * WAV-Upload ist Owner-Entscheidung: als Option vorhanden, Default AN (Gerätetest-
     * Rückmeldung - kehrt die frühere Entscheidung "Default AUS" aus Plan 13, Punkt 7 um). WAVs
     * sind gross und koennen Sprache Dritter enthalten - eine andere Datenschutzkategorie als
     * reine Pegelwerte (Plan 8.4.1) - deshalb bleibt es eine abschaltbare Option, nur der Default
     * hat sich geaendert.
     */
    var driveUploadWav: Boolean
        get() = prefs.getBoolean("drive_upload_wav", true)
        set(value) = prefs.edit().putBoolean("drive_upload_wav", value).apply()

    var driveSyncLastSuccessAt: Long
        get() = prefs.getLong("drive_sync_last_success_at", 0L)
        set(value) = prefs.edit().putLong("drive_sync_last_success_at", value).apply()

    /** Gezaehlt ueber Zyklen hinweg, nicht nur innerhalb eines Laufs - sonst wuerde ein
     * Prozess-Neustart die Warnschwelle aus Plan 8.4.6 (n=6) nie erreichen koennen. */
    var driveSyncFehlschlaegeInFolge: Int
        get() = prefs.getInt("drive_sync_failures", 0)
        set(value) = prefs.edit().putInt("drive_sync_failures", value).apply()

    /**
     * Wird gesetzt, wenn der konfigurierte Ordner nicht mehr auffindbar ist (Plan 8.4.6:
     * "Ordner geloescht -> Sync pausieren, Nutzer zur Neuwahl auffordern - NICHT stillschweigend
     * in 'Meine Ablage' schreiben"). Bewusst ein eigenes Flag statt driveSyncEnabled=false zu
     * setzen: Sonst saehe der Sync-Schalter in den Einstellungen so aus, als haette der Nutzer
     * ihn selbst ausgeschaltet - er soll aber sichtbar bleiben, dass die App etwas zu melden hat.
     * Eine neue Ordnerwahl (driveFolderId aendert sich) setzt dieses Flag zurueck.
     */
    var driveOrdnerBlockiert: Boolean
        get() = prefs.getBoolean("drive_ordner_blockiert", false)
        set(value) = prefs.edit().putBoolean("drive_ordner_blockiert", value).apply()

    // ---------------------------------------------------------------- M6: Sicherheit

    /**
     * Diagnose-/Rohdaten-Log (Plan Abschnitt 6): "standardmaessig aus". Kein Geheimnis - anders
     * als [ntfyTopic] & Co. bleibt dieser Schalter in den unverschluesselten Einstellungen.
     */
    var diagnoseLoggingAktiv: Boolean
        get() = prefs.getBoolean("diagnose_logging_enabled", false)
        set(value) = prefs.edit().putBoolean("diagnose_logging_enabled", value).apply()

    // ---------------------------------------------------------------- Diagnose & Observability (Konzept)

    /**
     * Steuert die Remote-Uebertragung technischer Fehler an Sentry (Konzept Abschnitt 8.3).
     * Standardmaessig AUS (Opt-In), erfordert ausdrueckliche Zustimmung des Nutzers.
     */
    var remoteDiagnoseAktiv: Boolean
        get() = prefs.getBoolean("remote_diagnose_enabled", false)
        set(value) = prefs.edit().putBoolean("remote_diagnose_enabled", value).apply()

    /** Letzte erzeugte oder empfangene Diagnose-ID fuer die UI. */
    var letzteDiagnoseId: String?
        get() = prefs.getString("letzte_diagnose_id", null)
        set(value) = prefs.edit().putString("letzte_diagnose_id", value).apply()
}

/**
 * Erzeugt die Keystore-gestuetzte [EncryptedSharedPreferences] fuer [SettingsManager.securePrefs].
 * `null` bei Fehlschlag statt einer Exception, die den ganzen [SettingsManager] unbenutzbar
 * machen wuerde - ein Keystore-Problem auf einem einzelnen Geraet (kaputte ROM, Herstellerbug)
 * darf nicht die gesamte App-Konfiguration lahmlegen. Eigene Datei ("noise_settings_secure")
 * statt derselben wie [SettingsManager.prefs]: EncryptedSharedPreferences erwartet eine Datei,
 * die ausschliesslich ihr gehoert.
 */
private fun sicherePraefsOderNull(context: Context): SharedPreferences? = runCatching {
    val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    EncryptedSharedPreferences.create(
        context,
        "noise_settings_secure",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
}.onFailure { Log.w(TAG, "Verschluesselte Ablage nicht verfuegbar, falle auf Klartext zurueck", it) }
    .getOrNull()

/**
 * Erzeugt ein Topic aus [SecureRandom]. 32 Zeichen aus 62 moeglichen sind rund 190 Bit - beim
 * oeffentlichen ntfy-Server ist das die gesamte Sicherheit der Alarmierung, ein sprechender oder
 * kurzer Name waere hier fahrlaessig.
 */
fun erzeugeNtfyTopic(zufall: java.security.SecureRandom = java.security.SecureRandom()): String {
    val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    return (1..32).map { alphabet[zufall.nextInt(alphabet.length)] }.joinToString("")
}
