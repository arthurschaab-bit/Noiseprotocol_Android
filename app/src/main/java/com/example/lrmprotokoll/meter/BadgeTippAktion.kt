package com.example.lrmprotokoll.meter

/** Was ein Tipp auf den Bluetooth-Statusbadge ausloesen soll. */
enum class BadgeTippAktion {
    /** Sofort einen neuen Verbindungsanlauf anstossen - ohne Scan, ohne Dialog. */
    ERNEUT_VERBINDEN,

    /** Den Kopplungsdialog oeffnen (Geraetesuche). */
    KOPPLUNGSDIALOG,
}

/**
 * F-03, Audit-Punkt 2: "Im Badge einen sekundaeren CTA 'Erneut verbinden' anbieten, wenn
 * `state == FAILED`".
 *
 * Owner-Entscheidung 29.09.2026: **kein zusaetzlicher Knopf, sondern derselbe Tipp mit anderem
 * Ziel.** Ein sekundaerer CTA haette in eine 21 dp hohe Pille in der TopAppBar gemusst; der Tipp
 * ist ohnehin schon da. Der Audit rechnet fuer die manuelle Erholung mit "2 Taps + 10 s Scan"
 * heute gegen "1 Tap, ohne Scan" danach - genau das ergibt sich hieraus.
 *
 * Die Bedingung ist bewusst eng: **nur** [ConnectionState.FAILED] und **nur** mit gepinntem
 * Geraet. Ohne gepinntes Geraet gibt es nichts, wohin ein Anlauf gehen koennte - dort bleibt der
 * Kopplungsdialog der richtige Weg. In jedem anderen Zustand ebenso: wer bei einer laufenden
 * Verbindung auf das Badge tippt, will das Geraet wechseln, nicht dieselbe Verbindung neu
 * aufbauen.
 *
 * Reine Funktion statt einer `if`-Kaskade im Composable, damit genau diese Abgrenzung ohne
 * Compose pruefbar ist.
 */
fun badgeTippAktion(
    zustand: ConnectionState,
    geraetGepinnt: Boolean,
): BadgeTippAktion =
    if (zustand == ConnectionState.FAILED && geraetGepinnt) {
        BadgeTippAktion.ERNEUT_VERBINDEN
    } else {
        BadgeTippAktion.KOPPLUNGSDIALOG
    }
