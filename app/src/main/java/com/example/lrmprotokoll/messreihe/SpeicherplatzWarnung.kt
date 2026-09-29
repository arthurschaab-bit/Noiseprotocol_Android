package com.example.lrmprotokoll.messreihe

import android.content.Context
import android.os.StatFs

/**
 * Schwelle, ab der vor dem Start einer Messung gewarnt wird (Befund F-10).
 *
 * **5 GB, Owner-Entscheidung vom 29.09.2026.** Der Audit legt keine Zahl fest, und der
 * Umsetzungs-Prompt zu Phase 5 verlangt ausdruecklich, sie nicht zu erfinden (AGENTS.md §8a).
 */
const val SPEICHERPLATZ_WARNSCHWELLE_BYTES = 5L * 1024 * 1024 * 1024

/**
 * Freier Speicher auf dem Datentraeger, auf dem die App ihre Aufnahmen ablegt.
 *
 * Bewusst `getExternalFilesDir` und nicht der interne Speicher: dorthin schreibt
 * [ermittleSpeicherplatz] die WAV-Dateien, und die machen den Verbrauch einer Messung aus.
 * Faellt die Abfrage aus (kein externer Speicher eingehaengt), gibt es `null` — dann wird nicht
 * gewarnt, statt eine Warnung auf einen geratenen Wert zu stuetzen.
 */
fun freierSpeicherBytes(context: Context): Long? {
    val verzeichnis = context.getExternalFilesDir(null) ?: return null
    return runCatching { StatFs(verzeichnis.absolutePath).availableBytes }.getOrNull()
}

/**
 * Entscheidet, ob vor dem Messstart gewarnt wird.
 *
 * Reine Funktion, damit die Regel ohne Android testbar ist — die Messung des freien Speichers
 * steckt in [freierSpeicherBytes].
 *
 * **Warnen, nicht blockieren.** Der Audit beschreibt F-10 als "eine Pruefung vor dem Messstart,
 * mit verstaendlicher Meldung statt eines stillen Fehlschlags". Eine Messung zu verhindern waere
 * bei einem Beweiswerkzeug die staerkere Aussage: wer trotz knappem Speicher messen will, hat
 * dafuer moeglicherweise einen guten Grund, und eine verweigerte Messung laesst sich nicht
 * nachholen. Die Entscheidung bleibt beim Nutzer, er trifft sie nur nicht mehr blind.
 */
fun sollVorSpeicherplatzWarnen(
    freieBytes: Long?,
    schwelleBytes: Long = SPEICHERPLATZ_WARNSCHWELLE_BYTES,
): Boolean = freieBytes != null && freieBytes < schwelleBytes
