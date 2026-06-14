package com.example.lrmprotokoll

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("noise_settings", Context.MODE_PRIVATE)

    var threshold: Int
        get() = prefs.getInt("threshold", 3000)
        set(value) = prefs.edit().putInt("threshold", value).apply()

    var preRollSeconds: Int
        get() = prefs.getInt("pre_roll", 2)
        set(value) = prefs.edit().putInt("pre_roll", value).apply()

    var recordDurationSeconds: Int
        get() = prefs.getInt("duration", 3)
        set(value) = prefs.edit().putInt("duration", value).apply()

    var aiConfidenceThreshold: Float
        get() = prefs.getFloat("ai_confidence", 0.3f)
        set(value) = prefs.edit().putFloat("ai_confidence", value).apply()

    var audioSampleRate: Int
        get() = prefs.getInt("sample_rate", 16000)
        set(value) = prefs.edit().putInt("sample_rate", value).apply()
}
