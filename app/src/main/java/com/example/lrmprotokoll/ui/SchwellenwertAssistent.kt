package com.example.lrmprotokoll.ui

/**
 * PROMPT_M10_FUNKTIONEN.md F1 (Schwellenwert-Assistent): reine Ableitung vom aktuellen
 * Mikrofonpegel auf einen Schwellenvorschlag, getrennt von der Compose-Anzeige, damit die
 * Kappung auf den vom Slider erlaubten Bereich (30..100 dB, siehe SettingsScreen/
 * MicrophoneControlCard) ohne Emulator per JVM-Unit-Test prüfbar ist. Absichtlich der
 * MIKROFON-Pegel, nicht der eines verbundenen Messgeräts: die Schwelle wird ausschließlich
 * gegen [com.example.lrmprotokoll.audio.AudioRecordingService]s Mikrofonpfad geprüft (seit
 * PR #43 zeichnet ein verbundenes Messgerät ohnehin durchgehend auf, ohne eigene Schwelle) -
 * ein Vorschlag auf Basis des kalibrierten Messgerätewerts würde eine Schwelle setzen, die auf
 * der falschen Skala liegt.
 */
private const val SCHWELLE_MIN = 30f
private const val SCHWELLE_MAX = 100f
private const val SICHERHEITSABSTAND_DB = 5f

fun schwellenvorschlagAufAktuellemPegel(aktuellerMikrofonPegel: Double): Float =
    aktuellerMikrofonPegel.toFloat().coerceIn(SCHWELLE_MIN, SCHWELLE_MAX)

fun schwellenvorschlagMitSicherheitsabstand(aktuellerMikrofonPegel: Double): Float =
    (aktuellerMikrofonPegel.toFloat() + SICHERHEITSABSTAND_DB).coerceIn(SCHWELLE_MIN, SCHWELLE_MAX)
