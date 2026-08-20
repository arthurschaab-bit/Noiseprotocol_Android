package com.example.lrmprotokoll.diagnose

/**
 * Kontextinformationen zur laufenden App-Sitzung und dem Geraetestatus (Konzept Abschnitt 6).
 *
 * Streng bereinigt (keine PII, keine Seriennummer, keine absolute Pfade, keine Nutzerinhalte).
 */
data class DiagnosticContext(
    val sessionId: String = "",
    val monitoringSessionId: Long? = null,
    val activeScreen: String = "",
    val lastUserAction: String = "",
    val monitoringActive: Boolean = false,
    val audioRecordingActive: Boolean = false,
    val alarmActive: Boolean = false,
    val appVersion: String = "",
    val versionCode: Long = 0,
    val buildType: String = "debug",
    val androidRelease: String = "",
    val sdkInt: Int = 0,
    val deviceModel: String = "",
    val manufacturer: String = "",
    val isLowRamDevice: Boolean = false,
    val networkType: String = "UNKNOWN",
    val isOnline: Boolean = true,
)
