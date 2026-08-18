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
}
