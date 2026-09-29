package com.example.lrmprotokoll.messreihe

import com.example.lrmprotokoll.data.NoiseRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Gespeicherter Wert fuer eine erfolgreiche Inferenz ohne Treffer oberhalb der Schwelle. */
const val NICHT_ERKANNT_LABEL = "Nicht erkannt"

/**
 * Alle Schreibweisen, unter denen "kein Treffer" in `detectedLabel` stehen kann.
 *
 * Der gespeicherte Wert ist woertlich die deutsche Oberflaechenzeichenkette
 * `R.string.status_not_recognized`; auf einem englischen Geraet entstand frueher der englische
 * Wert. Beide muessen beim Lesen dasselbe bedeuten, sonst rutscht ein Datensatz in den Zweig
 * "erkannt" und die App zeigt "Nicht erkannt" als waere es eine erkannte Klasse — genau das,
 * was F-17 abstellen sollte.
 *
 * **Offen fuer den Owner (AGENTS.md §8a):** ein Sentinel, der eine uebersetzte Zeichenkette ist,
 * bricht bei jeder weiteren Sprache und bei jeder Umformulierung des deutschen Textes. Sauber
 * waere ein sprachunabhaengiger Marker in `detectedLabel` (oder eine eigene Spalte). Das
 * veraendert gespeicherte Daten und ist deshalb keine Entscheidung dieses Fixes.
 */
val NICHT_ERKANNT_LABELS = setOf(NICHT_ERKANNT_LABEL, "Not recognized")

/**
 * Das Label nur dann, wenn die KI wirklich etwas erkannt hat — sonst `null`.
 *
 * Vor F-17 blieb `detectedLabel` bei einer Inferenz ohne Treffer `null`, und an sieben Stellen
 * bedeutet `detectedLabel != null` bis heute "die KI hat etwas erkannt". Seit F-17 steht dort
 * stattdessen ein Sentinel, der diese Pruefungen erfuellt, ohne sie zu meinen. Wer die
 * Unterscheidung "erkannt / nicht erkannt" braucht, nimmt [klassifizierungsStatus]; wer nur ein
 * verwertbares Label will, nimmt diese Funktion.
 */
fun erkanntesLabel(detectedLabel: String?): String? = detectedLabel?.takeUnless { it.isBlank() || it in NICHT_ERKANNT_LABELS }

enum class KlassifizierungsStatus { DEAKTIVIERT, AUSSTEHEND, NICHT_ERKANNT, ERKANNT }

/** Ohne Audiopfad gibt es keinen Klassifizierungsstatus. */
fun klassifizierungsStatus(record: NoiseRecord, aiMode: String): KlassifizierungsStatus? {
    if (record.filePath.isBlank()) return null
    val label = record.detectedLabel
    return when {
        label == null -> if (aiMode == "OFF") KlassifizierungsStatus.DEAKTIVIERT else KlassifizierungsStatus.AUSSTEHEND
        label in NICHT_ERKANNT_LABELS -> KlassifizierungsStatus.NICHT_ERKANNT
        else -> KlassifizierungsStatus.ERKANNT
    }
}

/**
 * Gruppiert Aufnahmen nach Kalendertag (Format dd.MM.yyyy) für die Tagesabschnitte auf dem
 * Home-Screen. Reihenfolge der Gruppen folgt der Reihenfolge der ersten je Tag angetroffenen
 * Aufnahme in [records] (i.d.R. bereits absteigend nach Zeit sortiert aus dem DAO).
 */
fun gruppiereNachTag(
    records: List<NoiseRecord>,
    locale: Locale = Locale.getDefault(),
): Map<String, List<NoiseRecord>> {
    val formatter = SimpleDateFormat("dd.MM.yyyy", locale)
    return records.groupBy { formatter.format(Date(it.timestamp)) }
}

/**
 * Aufnahmen, für die eine KI-Nachklassifizierung sinnvoll ist: weder KI- noch manuelles Label
 * vorhanden, und es existiert überhaupt ein Audiopfad (rein Messgerät-getriggerte Einträge ohne
 * Aufnahme haben keinen).
 */
fun unklassifizierteAufnahmen(records: List<NoiseRecord>): List<NoiseRecord> =
    records.filter { it.detectedLabel == null && it.label == null && it.filePath.isNotBlank() }
