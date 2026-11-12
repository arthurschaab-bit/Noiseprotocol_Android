package com.example.lrmprotokoll.messreihe

import com.example.lrmprotokoll.data.KALIBRIER_SOLLWERT_DB

/**
 * Erkennt aus dem Pegelverlauf einer Kalibrierung (Messgeraet am Kalibrator PCE-SC 43,
 * 94/104/114 dB bei 1 kHz) die Anzeigeabweichung vor und nach der Justierung sowie das Ende.
 *
 * **Das ist eine begruendete Schaetzung, kein geprueftes Verfahren** (Owner-Vorgabe 12.11.2026:
 * "best guess, die Messreihe eines Kalibriermorgens liefere ich morgen"). Grundlage ist nur die
 * Beschreibung des Owners - zunaechst einige Sekunden stabil zwischen 92 und 96 dB, danach
 * Spruenge zwischen etwa 94, 105 und einem weiteren Wert - und das Datenblatt des Kalibrators
 * (Stabilisierungszeit 10 s). Alle Schwellen sind Konstruktorparameter, damit sie an echten
 * Daten nachgezogen werden koennen, ohne die Logik zu aendern. Der Nutzer sieht die erkannten
 * Werte vor dem Speichern und kann sie korrigieren (siehe [KalibrierAnzeige]).
 *
 * Verfahren:
 * - **Plateau:** Alle Messwerte der letzten [fensterMs] liegen in [plateauMinDb]..[plateauMaxDb]
 *   und streuen um hoechstens [maxSpannDb]. Der Plateau-Median minus [sollwertDb] ist die
 *   Abweichung. Das noch driftende Einschwingen des Kalibrators erfuellt die Spannweite nicht.
 * - **Vorher:** das erste stabile Plateau. **Nachher:** das zuletzt gesehene stabile Plateau
 *   (nach einer Justierung steht dort der neue Wert; ohne Justierung gleicht es dem ersten).
 *   Plateaus bei 104/114 dB liegen ausserhalb des Fensters und zaehlen nicht.
 * - **Ende:** nach mindestens einem Plateau liegt der Pegel [endeDauerMs] lang unter
 *   [endeUnterDb] (Kalibrator abgenommen, Umgebungsgeraeusch).
 * - **Luecke:** Fehlt [maxLueckeMs] lang jeder Wert, wird das Fenster verworfen.
 */
class KalibrierErkennung(
    private val sollwertDb: Double = KALIBRIER_SOLLWERT_DB,
    private val plateauMinDb: Double = 92.0,
    private val plateauMaxDb: Double = 96.0,
    private val fensterMs: Long = 6_000,
    private val maxSpannDb: Double = 1.0,
    private val endeUnterDb: Double = 85.0,
    private val endeDauerMs: Long = 4_000,
    private val maxLueckeMs: Long = 3_000,
) {
    private val fenster = ArrayDeque<Pair<Long, Double>>()
    private var letzterZeitpunkt: Long? = null
    private var unterSeit: Long? = null
    private var vorher: Double? = null
    private var nachher: Double? = null
    private var endeErkannt = false
    private var aktuell: Double? = null

    fun nimm(zeitMs: Long, pegelDb: Double) {
        val letzter = letzterZeitpunkt
        if (letzter != null && zeitMs - letzter > maxLueckeMs) {
            fenster.clear()
            unterSeit = null
        }
        letzterZeitpunkt = zeitMs
        aktuell = pegelDb

        fenster.addLast(zeitMs to pegelDb)
        while (fenster.isNotEmpty() && zeitMs - fenster.first().first > fensterMs) fenster.removeFirst()

        if (istStabil()) {
            val abweichung = fenster.map { it.second }.sorted().let { it[it.size / 2] } - sollwertDb
            if (vorher == null) vorher = abweichung
            nachher = abweichung
        }

        if (vorher != null && pegelDb < endeUnterDb) {
            val seit = unterSeit ?: zeitMs.also { unterSeit = it }
            if (zeitMs - seit >= endeDauerMs) endeErkannt = true
        } else {
            unterSeit = null
        }
    }

    private fun istStabil(): Boolean {
        // Das Fenster muss (fast) die volle Dauer abdecken, sonst gaelte ein einzelner Wert als Plateau.
        if (fenster.size < 2 || fenster.last().first - fenster.first().first < fensterMs * 0.8) return false
        val werte = fenster.map { it.second }
        return werte.all { it in plateauMinDb..plateauMaxDb } && (werte.max() - werte.min()) <= maxSpannDb
    }

    fun anzeige() = KalibrierAnzeige(
        pegelDb = aktuell,
        abweichungVorherDb = vorher,
        abweichungNachherDb = nachher,
        endeErkannt = endeErkannt,
    )
}

/** Stand der Kalibrier-Erkennung fuer die Oberflaeche; Abweichungen sind Pegel minus Sollwert. */
data class KalibrierAnzeige(
    val pegelDb: Double? = null,
    val abweichungVorherDb: Double? = null,
    val abweichungNachherDb: Double? = null,
    val endeErkannt: Boolean = false,
)
