package com.example.lrmprotokoll.messreihe

import com.example.lrmprotokoll.data.NoiseRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Der Wert, der fuer "Inferenz gelaufen, kein Treffer oberhalb der Schwelle" gespeichert wird.
 *
 * **Sprachunabhaengig, Owner-Entscheidung 29.09.2026.** Bis dahin stand hier woertlich die
 * deutsche Oberflaechenzeichenkette `R.string.status_not_recognized`. Das brach in zwei
 * Richtungen: auf einem englischen Geraet entstand frueher der englische Wert (deshalb traegt
 * [NICHT_ERKANNT_LABELS] ihn bis heute mit), und jede Umformulierung des deutschen Textes haette
 * Altdatensaetze still in den Zweig "erkannt" rutschen lassen — die App haette dann
 * "Nicht erkannt" angezeigt, als waere es eine erkannte Klasse. Genau das sollte F-17 abstellen.
 *
 * Die Doppelunterstriche sind Absicht: der Wert ist kein YAMNet-Label und soll auch nicht wie
 * eines aussehen, falls er je ungefiltert irgendwo landet. Angezeigt wird er nirgends — dafuer
 * sorgen [erkanntesLabel] und [klassifizierungsStatus].
 */
const val NICHT_ERKANNT_MARKER = "__nicht_erkannt__"

/**
 * Alle Werte, die "kein Treffer" bedeuten: der aktuelle Marker plus die beiden
 * Oberflaechenzeichenketten, die vor dem 29.09.2026 geschrieben wurden.
 *
 * **Keine Migration.** Bestandsdatensaetze behalten ihren alten Wert und werden hier weiterhin
 * richtig verstanden; nur neu geschriebene tragen den Marker. Eine Migration waere eine
 * Schemaaenderung fuer einen Gewinn, den diese Menge ohne Risiko liefert.
 */
val NICHT_ERKANNT_LABELS = setOf(NICHT_ERKANNT_MARKER, "Nicht erkannt", "Not recognized")

/** `true`, wenn die KI gelaufen ist und nichts erkannt hat — in jeder je gespeicherten Form. */
fun istNichtErkannt(detectedLabel: String?): Boolean = detectedLabel != null && detectedLabel in NICHT_ERKANNT_LABELS

/**
 * Das Label nur dann, wenn die KI wirklich etwas erkannt hat — sonst `null`.
 *
 * Vor F-17 blieb `detectedLabel` bei einer Inferenz ohne Treffer `null`, und an neun Stellen
 * bedeutet `detectedLabel != null` bis heute "die KI hat etwas erkannt". Seit F-17 steht dort
 * stattdessen ein Sentinel, der diese Pruefungen erfuellt, ohne sie zu meinen. Wer die
 * Unterscheidung "erkannt / nicht erkannt" braucht, nimmt [klassifizierungsStatus]; wer nur ein
 * verwertbares Label will, nimmt diese Funktion.
 */
fun erkanntesLabel(detectedLabel: String?): String? = detectedLabel?.takeUnless { it.isBlank() || istNichtErkannt(it) }

/**
 * Ob ein Datensatz zu einer Textsuche passt, wenn der Nutzer nach "nicht erkannt" sucht.
 *
 * Der Marker ist nicht mehr lesbar, eine reine `contains`-Suche ueber `detectedLabel` faende ihn
 * also nicht mehr. Damit die Suche weiter funktioniert, werden die bekannten Schreibweisen
 * mitgeprueft — ohne Context, weil die Filter reine Funktionen bleiben sollen.
 */
fun nichtErkanntPasstZurSuche(
    detectedLabel: String?,
    suchtext: String,
): Boolean {
    if (!istNichtErkannt(detectedLabel)) return false
    val frage = suchtext.trim().lowercase()
    if (frage.isBlank()) return false
    return NICHT_ERKANNT_LABELS.any { it != NICHT_ERKANNT_MARKER && it.lowercase().contains(frage) }
}

enum class KlassifizierungsStatus { DEAKTIVIERT, AUSSTEHEND, NICHT_ERKANNT, ERKANNT }

/** Ohne Audiopfad gibt es keinen Klassifizierungsstatus. */
fun klassifizierungsStatus(record: NoiseRecord, aiMode: String): KlassifizierungsStatus? {
    if (record.filePath.isBlank()) return null
    val label = record.detectedLabel
    return when {
        label == null -> if (aiMode == "OFF") KlassifizierungsStatus.DEAKTIVIERT else KlassifizierungsStatus.AUSSTEHEND
        istNichtErkannt(label) -> KlassifizierungsStatus.NICHT_ERKANNT
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
