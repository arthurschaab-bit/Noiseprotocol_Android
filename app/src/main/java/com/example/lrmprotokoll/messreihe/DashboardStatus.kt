package com.example.lrmprotokoll.messreihe

import com.example.lrmprotokoll.meter.ConnectionState
import com.example.lrmprotokoll.meter.label
import java.util.Locale

/**
 * Reine Ableitungslogik für das Live-Status-Dashboard auf dem Home-Screen (M7c Aufgabe 1) -
 * getrennt von der Compose-Anzeige, damit sie ohne Emulator per JVM-Unit-Test prüfbar ist
 * (dasselbe Muster wie [zaehleReconnects]/[leiteAusfallbaenderAb]).
 *
 * [betriebsartText] unterscheidet die beiden Betriebsarten aus [com.example.lrmprotokoll.audio.AudioRecordingService]:
 * mit gepinntem Messgerät zeigt sie dessen Verbindungszustand, ohne gepinntes Messgerät läuft
 * die Überwachung weiterhin über das Mikrofon (bestehendes Verhalten, Bestandsaufnahme
 * "Kein Mikrofon-Only-Fall vergessen").
 */
data class DashboardAnzeige(
    val dienstAktiv: Boolean,
    val betriebsartText: String,
    val laufzeitText: String?,
    val pegelText: String?,
    val dauerText: String? = null,
)

fun leiteDashboardAnzeigeAb(
    dienstAktiv: Boolean,
    geraetGepinnt: Boolean,
    verbindungszustand: ConnectionState,
    sessionStartedAtMillis: Long?,
    jetztMillis: Long,
    letzterPegel: Double?,
): DashboardAnzeige {
    if (!dienstAktiv) {
        return DashboardAnzeige(
            dienstAktiv = false,
            betriebsartText = "Inaktiv",
            laufzeitText = null,
            pegelText = null,
            dauerText = null,
        )
    }

    val betriebsartText = if (geraetGepinnt) {
        "Messgerät: ${verbindungszustand.label()}"
    } else {
        "Mikrofon-Überwachung"
    }

    // Nur eine laufende Session hat eine Laufzeit - sessionStartedAtMillis ist null, solange
    // kein Messgeraet gepinnt ist oder dessen Session noch nicht eroeffnet wurde.
    val dauerText = sessionStartedAtMillis?.let { formatiereDauer(jetztMillis - it) }
    val laufzeitText = dauerText?.let { "Läuft seit $it" }

    // Ein Pegel ohne STREAMING waere ein veralteter Restwert aus einer frueheren Verbindung -
    // dasselbe Prinzip wie in MeterScreen (frame != null && connectionState == STREAMING).
    val pegelText = letzterPegel
        ?.takeIf { verbindungszustand == ConnectionState.STREAMING }
        ?.let { String.format(Locale.GERMANY, "%.1f dB", it) }

    return DashboardAnzeige(
        dienstAktiv = true,
        betriebsartText = betriebsartText,
        laufzeitText = laufzeitText,
        pegelText = pegelText,
        dauerText = dauerText,
    )
}

/** "M:SS" unter einer Stunde, "H:MM:SS" ab einer Stunde - negative/kaputte Eingaben (Uhr-
 * Neusynchronisation) werden auf 0 gekappt statt eine negative Laufzeit anzuzeigen. */
fun formatiereDauer(millis: Long): String {
    val gesamtSekunden = (millis / 1000).coerceAtLeast(0)
    val stunden = gesamtSekunden / 3600
    val minuten = (gesamtSekunden % 3600) / 60
    val sekunden = gesamtSekunden % 60
    return if (stunden > 0) {
        String.format(Locale.GERMANY, "%d:%02d:%02d", stunden, minuten, sekunden)
    } else {
        String.format(Locale.GERMANY, "%d:%02d", minuten, sekunden)
    }
}

/**
 * Welcher Pegel im Cockpit steht und wie er zu lesen ist.
 *
 * [nurLive] heißt: das Messgerät liefert Werte, aber es läuft keine Aufzeichnung. Vor S-3 war
 * dieser Zustand unmöglich, weil "verbinden" den Vordergrunddienst startete - seit F-02 ist er
 * der Normalfall und muss sichtbar sein, ohne wie eine laufende Messung auszusehen
 * (Owner-Entscheidung 27.09.2026, nach Gerätetest A5/B4 zu PR #216).
 */
data class PegelAnzeige(
    val wert: Double?,
    val nurLive: Boolean,
    val kalibriert: Boolean,
)

/**
 * Der kalibrierte Wert des Messgeräts hat Vorrang und braucht den Dienst nicht - der
 * Mikrofonpegel entsteht dagegen erst in der Aufzeichnung und ist ohne sie nicht vorhanden.
 * Ein Pegel ohne [ConnectionState.STREAMING] wäre ein Restwert aus einer früheren Verbindung,
 * dieselbe Schranke wie in [leiteDashboardAnzeigeAb] und im MeterScreen.
 */
fun leitePegelAnzeigeAb(
    dienstAktiv: Boolean,
    verbindungszustand: ConnectionState,
    messgeraetPegel: Double?,
    mikrofonPegel: Double?,
): PegelAnzeige {
    val messgeraetWert = messgeraetPegel?.takeIf { verbindungszustand == ConnectionState.STREAMING }
    if (messgeraetWert != null) {
        return PegelAnzeige(wert = messgeraetWert, nurLive = !dienstAktiv, kalibriert = true)
    }
    if (dienstAktiv) {
        return PegelAnzeige(wert = mikrofonPegel, nurLive = false, kalibriert = false)
    }
    return PegelAnzeige(wert = null, nurLive = false, kalibriert = false)
}
