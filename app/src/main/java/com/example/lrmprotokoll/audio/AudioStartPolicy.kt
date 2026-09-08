package com.example.lrmprotokoll.audio

/**
 * Entscheidet, ob ein neu gestarteter/re-erzeugter AudioRecordingService die Mikrofonaufnahme
 * wieder aufnehmen soll.
 *
 * Wichtig: Ein Intent ohne START-Extra darf eine zuvor laufende Audioaufnahme nicht verlieren.
 * Genau dieser Zustand trat im HW-Test auf: Service/PCE kamen nach dem Recreate wieder, der
 * Mikrofonpfad blieb jedoch aus. Ein expliziter Audio-/Service-Stop setzt den persistenten
 * [com.example.lrmprotokoll.data.SettingsManager.audioMonitoringWasActive]-Zustand vorher auf
 * false und verhindert damit eine ungewollte Wiederaufnahme.
 */
internal fun sollAudioMonitoringStarten(
    expliziterStart: Boolean,
    audioWarVorherAktiv: Boolean,
): Boolean = expliziterStart || audioWarVorherAktiv
