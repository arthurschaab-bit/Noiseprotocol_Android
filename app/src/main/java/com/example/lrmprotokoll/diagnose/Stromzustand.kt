package com.example.lrmprotokoll.diagnose

/**
 * Modellierung des Stromversorgungs- und Ladezustands ohne Android-Abhaengigkeiten
 * (gemaess docs/PROMPT_FIX_LADEZUSTAND_PROTOKOLLIEREN.md, Befund G).
 */
data class Stromzustand(
    val quelle: Quelle,
    val status: Status,
    val prozent: Int?,
) {
    enum class Quelle(val label: String) {
        NETZTEIL("Netzteil"),
        USB("USB"),
        KABELLOS("kabellos"),
        KEINE("keine"),
    }

    enum class Status(val label: String) {
        LAEDT("lädt"),
        VOLL("voll"),
        ENTLAEDT("entlädt"),
        UNBEKANNT("unbekannt"),
    }
}

/**
 * Ermittelt den naechsten Protokolleintrag bei einer Zustandsaenderung oder `null`, wenn kein
 * Eintrag geschrieben werden soll.
 *
 * Regeln gemaess Auftrag:
 * - Praefix aller Texte: "Stromversorgung:" bzw. "Stromversorgung beim Start:" (fuer Filterung in M-Lebenszyklus).
 * - Erster Aufruf (vorher == null): Ausgangseintrag mit aktuellem Zustand.
 * - Eintrag bei jeder Aenderung von `quelle` oder `status`.
 * - Beim Entladen zusaetzlich bei jedem Schritt ueber eine 10-%-Grenze nach unten (z.B. 91% -> 89%).
 * - Sonst kein Eintrag (um das Protokoll vor Broadcast-Fluten zu schuetzen).
 */
fun naechsterEintrag(vorher: Stromzustand?, jetzt: Stromzustand): String? {
    val prozentText = jetzt.prozent?.let { "Akku $it %" } ?: "Akku unbekannt"

    if (vorher == null) {
        return "Stromversorgung beim Start: ${jetzt.quelle.label}, $prozentText, ${jetzt.status.label}"
    }

    val quelleGeaendert = vorher.quelle != jetzt.quelle
    val statusGeaendert = vorher.status != jetzt.status

    if (quelleGeaendert) {
        return "Stromversorgung: ${vorher.quelle.label} → ${jetzt.quelle.label}, $prozentText, ${jetzt.status.label}"
    }

    if (statusGeaendert) {
        return "Stromversorgung: ${jetzt.quelle.label}, $prozentText, ${jetzt.status.label}"
    }

    // Beim Entladen zusaetzlich bei jedem Schritt ueber eine 10-%-Grenze nach unten
    if (jetzt.status == Stromzustand.Status.ENTLAEDT && vorher.prozent != null && jetzt.prozent != null) {
        val alteZehner = vorher.prozent / 10
        val neueZehner = jetzt.prozent / 10
        if (neueZehner < alteZehner) {
            return "Stromversorgung: ${jetzt.quelle.label}, $prozentText, ${jetzt.status.label}"
        }
    }

    return null
}
