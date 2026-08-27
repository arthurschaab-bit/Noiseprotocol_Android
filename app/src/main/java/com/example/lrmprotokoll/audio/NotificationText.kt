package com.example.lrmprotokoll.audio

import com.example.lrmprotokoll.meter.ConnectionState
import com.example.lrmprotokoll.meter.label
import java.util.Locale

/**
 * PROMPT_M10_FUNKTIONEN.md F4: reine Ableitung des Dauer-Notification-Texts, getrennt von
 * [AudioRecordingService.buildNotification] (Android-`Notification`-API, braucht Foreground-
 * Service/Mikrofon), damit sie ohne Emulator per JVM-Unit-Test prüfbar ist - dasselbe Muster wie
 * [com.example.lrmprotokoll.messreihe.leiteDashboardAnzeigeAb].
 *
 * [meterPegel] ist bereits `null`, solange kein Messgerät auf STREAMING steht (siehe
 * [AudioRecordingService]s `letzterMeterFrame`-KDoc) - hier keine zweite Zustandsprüfung.
 */
fun leiteNotificationTextAb(
    istMessgeraetGepinnt: Boolean,
    meterState: ConnectionState,
    meterPegel: Double?,
    mikrofonPegel: Double?,
): String = if (istMessgeraetGepinnt) {
    val pegelZusatz = meterPegel?.let { String.format(Locale.getDefault(), " (%.1f dB)", it) } ?: ""
    "Überwacht die Umgebungslautstärke. Messgerät: ${meterState.label()}$pegelZusatz"
} else if (mikrofonPegel != null) {
    "Überwacht die Umgebungslautstärke (Mikrofon): " +
        String.format(Locale.getDefault(), "%.1f dB", mikrofonPegel)
} else {
    "Die App überwacht die Umgebungslautstärke im Hintergrund."
}

/**
 * PROMPT_M10_FUNKTIONEN.md F4: bei DEGRADED/FAILED soll die Notification sichtbar unterscheidbar
 * sein, nicht nur eine geänderte Textzeile - der Nutzer soll den gestörten Zustand auch beim
 * blossen Blick auf die Statusleiste erkennen.
 */
fun istNotificationZustandGestoert(meterState: ConnectionState): Boolean =
    meterState == ConnectionState.DEGRADED || meterState == ConnectionState.FAILED
