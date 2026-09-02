package com.example.lrmprotokoll.audio

/**
 * KI-Umbau Etappe 2.1: die drei Baulärm-Klassengruppen aus Anhang A des Auftrags, deklarativ
 * statt im Code verstreut - Grundlage für den noisy-OR-Gruppen-Score ([berechneGruppenScore]).
 *
 * Gewichte: Kern 1.0 (eindeutig werkzeug-/baustellentypisch), Kontext 0.5 (kommt auf Baustellen
 * häufig vor, aber nicht ausschließlich), Impuls 0.3 (kurze, stoßartige Ereignisse - für sich
 * allein kein verlässliches Baulärm-Indiz, verstärken aber einen Treffer in Kern/Kontext).
 *
 * Abweichung von der Auftrags-Prosa in Abschnitt 2.1 bewusst zugunsten von Anhang A aufgelöst:
 * die Prosa nennt für "Impuls" nur "Boom, Crushing, Scrape", Anhang A führt zusätzlich Index 420
 * ("Explosion") als Impuls-Klasse. Da Anhang A die vollständigere, strukturierte Referenz ist
 * (der Test aus 2.2 prüft explizit gegen sie) und Etappe 1 (`RohdatenKlassen.kt`) Index 420
 * bereits per Kommentar der Impuls-Gruppe zugeordnet hatte, wird hier Anhang A gefolgt.
 *
 * Enthält bewusst NUR Kern/Kontext/Impuls - die "Ausschluss"-Klassen (Silence/Noise/...) fließen
 * laut Auftrag nicht in den Gruppen-Score ein und tauchen hier daher nicht auf.
 */
enum class Gruppe { KERN, KONTEXT, IMPULS }

data class KlassenGewicht(val index: Int, val name: String, val gruppe: Gruppe, val gewicht: Float)

val BAULAERM_KLASSEN: List<KlassenGewicht> = listOf(
    // Kern (Gewicht 1.0)
    KlassenGewicht(412, "Tools", Gruppe.KERN, 1.0f),
    KlassenGewicht(413, "Hammer", Gruppe.KERN, 1.0f),
    KlassenGewicht(414, "Jackhammer", Gruppe.KERN, 1.0f),
    KlassenGewicht(415, "Sawing", Gruppe.KERN, 1.0f),
    KlassenGewicht(417, "Sanding", Gruppe.KERN, 1.0f),
    KlassenGewicht(418, "Power tool", Gruppe.KERN, 1.0f),
    KlassenGewicht(419, "Drill", Gruppe.KERN, 1.0f),
    // Kontext (Gewicht 0.5)
    KlassenGewicht(310, "Truck", Gruppe.KONTEXT, 0.5f),
    KlassenGewicht(311, "Air brake", Gruppe.KONTEXT, 0.5f),
    KlassenGewicht(313, "Reversing beeps", Gruppe.KONTEXT, 0.5f),
    KlassenGewicht(337, "Engine", Gruppe.KONTEXT, 0.5f),
    KlassenGewicht(341, "Chainsaw", Gruppe.KONTEXT, 0.5f),
    KlassenGewicht(343, "Heavy engine (low frequency)", Gruppe.KONTEXT, 0.5f),
    KlassenGewicht(346, "Idling", Gruppe.KONTEXT, 0.5f),
    // Impuls (Gewicht 0.3)
    KlassenGewicht(420, "Explosion", Gruppe.IMPULS, 0.3f),
    KlassenGewicht(430, "Boom", Gruppe.IMPULS, 0.3f),
    KlassenGewicht(469, "Scrape", Gruppe.IMPULS, 0.3f),
    KlassenGewicht(472, "Crushing", Gruppe.IMPULS, 0.3f),
)
