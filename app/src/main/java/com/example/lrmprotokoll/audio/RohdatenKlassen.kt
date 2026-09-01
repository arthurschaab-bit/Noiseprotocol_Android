package com.example.lrmprotokoll.audio

/**
 * KI-Umbau Etappe 1.4: feste Untermenge von YAMNet-Klassenindizes, deren Frame-Scores in
 * [com.example.lrmprotokoll.data.KlassifikationsRohdaten] persistiert werden - nicht alle 521
 * Klassen, das wäre für eine 60-Sekunden-Aufnahme unnötig groß (siehe Entity-KDoc).
 *
 * Indizes gegen die tatsächlich eingebettete `yamnet.tflite`-Labelliste verifiziert (per
 * `yamnet_label_list.txt`, im Modell als Metadaten-Anhang enthalten) - nicht nur angenommen.
 * Bewusst inklusive der drei "Ausschluss"-Klassen (Silence/Noise/Environmental noise/White
 * noise/Inside small room): sie fließen in keinen Baulärm-Score ein, sind aber nützlich, um
 * stille von wirklich fehlklassifizierten Passagen zu unterscheiden - deshalb hier bereits
 * mitgespeichert, obwohl die Gruppierungslogik selbst erst Etappe 2 ist.
 *
 * Nur die Indizes - Gruppierung und Gewichtung (Kern/Kontext/Impuls) sind bewusst NICHT Teil
 * dieser Etappe, siehe `audio/Baulaermgruppen.kt` (Etappe 2).
 */
val ROHDATEN_KLASSEN_INDIZES: IntArray = intArrayOf(
    // Kern
    412, 413, 414, 415, 417, 418, 419,
    // Kontext
    310, 311, 313, 337, 341, 343, 346,
    // Impuls
    420, 430, 469, 472,
    // Ausschluss (fuer spaetere Stille-vs-Fehlklassifikation-Unterscheidung mitgespeichert)
    494, 507, 508, 514, 500,
)
