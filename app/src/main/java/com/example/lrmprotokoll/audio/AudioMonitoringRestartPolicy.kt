package com.example.lrmprotokoll.audio

/**
 * Entscheidet nach einem Audio-Lese-/Schreibfehler, ob das Mikrofon-Monitoring automatisch neu
 * gestartet werden soll, und wie oft.
 *
 * Bugfix (Support-Bundle-Auswertung 12.09.2026, Ausfall 0:10 bis 3:51 Uhr): [AudioRecordingService]
 * rief nach einem Lese-/Schreibfehler bisher `stopSelf()` auf, in der irrigen Annahme,
 * `START_STICKY` wuerde den Dienst danach neu starten. Das trifft nur zu, wenn das SYSTEM den
 * Prozess killt (z.B. Speichermangel) - nicht bei einem selbstinitiierten Stopp per `stopSelf()`.
 * Ergebnis war eine dauerhaft stumme Aufnahme bis zum naechsten manuellen App-Start.
 *
 * Reine Entscheidungslogik (analog [sollAudioMonitoringStarten]) statt Zustand direkt im Service:
 * so ist das Zaehl-/Rueckwetzungsverhalten ohne Robolectric/echte Audio-Hardware pruefbar. Nach
 * [MAX_AUDIO_MONITORING_RESTART_VERSUCHE] Fehlversuchen innerhalb desselben gleitenden Fensters
 * gibt der Aufrufer den automatischen Neustart auf - der Dienst (Messgeraet, Alarmierung,
 * Drive-Sync) bleibt dabei aktiv, nur der Mikrofonpfad bleibt aus und
 * `pruefeAudioSollIstAbweichung()` meldet den Ausfall weiterhin ueber die Notification, statt
 * spurlos zu verschwinden.
 */
internal const val MAX_AUDIO_MONITORING_RESTART_VERSUCHE = 5
internal const val AUDIO_MONITORING_RESTART_FENSTER_MS = 60_000L

internal data class AudioMonitoringRestartZustand(
    val versuche: Int = 0,
    val letzterVersuchMs: Long = 0L,
)

internal data class AudioMonitoringRestartEntscheidung(
    val neustartVersuchen: Boolean,
    val neuerZustand: AudioMonitoringRestartZustand,
)

internal fun entscheideUeberAudioMonitoringRestart(
    zustand: AudioMonitoringRestartZustand,
    jetzt: Long,
): AudioMonitoringRestartEntscheidung {
    val versucheImFenster = if (jetzt - zustand.letzterVersuchMs > AUDIO_MONITORING_RESTART_FENSTER_MS) {
        0
    } else {
        zustand.versuche
    }
    return if (versucheImFenster < MAX_AUDIO_MONITORING_RESTART_VERSUCHE) {
        AudioMonitoringRestartEntscheidung(
            neustartVersuchen = true,
            neuerZustand = AudioMonitoringRestartZustand(versucheImFenster + 1, jetzt),
        )
    } else {
        AudioMonitoringRestartEntscheidung(
            neustartVersuchen = false,
            neuerZustand = AudioMonitoringRestartZustand(versucheImFenster, jetzt),
        )
    }
}
