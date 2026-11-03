package com.example.lrmprotokoll.diagnose

enum class AusfallKlasse {
    NUTZER_STOPP,
    AKKU_LEER,
    KERNEL_NEUSTART,
    FRAMEWORK_NEUSTART,
    STILLSTAND_GERAET_LIEF,
    UNBEKANNT,
}

enum class WaechterLief {
    JA,
    NEIN,
    UNBEKANNT,
}

data class AusfallEinordnung(
    val klasse: AusfallKlasse,
    val waechterLief: WaechterLief,
    val letzteDatenAt: Long,
    val entdecktAt: Long,
    val belege: Map<String, Any?>,
) {
    fun details(): Map<String, Any?> =
        belege +
            mapOf(
                "klassifikation" to klasse.name,
                "waechterLief" to waechterLief.name.lowercase(),
                "letzteDatenAt" to letzteDatenAt,
                "entdecktAt" to entdecktAt,
            )
}

/**
 * Ordnet eine Datenluecke nur bei belastbarer Relation von Uptime und Wanduhr-Abstand ein.
 * Zwei Stunden Abstand zur Vergleichsgrenze fangen die im Auftrag getesteten Uhrspruenge ab;
 * im Grenzbereich bleibt die Klasse UNBEKANNT.
 */
object AusfallKlassifikator {
    private const val MINDESTLUECKE_MS = 5 * 60 * 1000L
    private const val UHR_UNSICHERHEIT_MS = 2 * 60 * 60 * 1000L

    fun klassifizieren(
        letzterZustand: LetzterZustand?,
        letzteDatenAt: Long?,
        waechterLetzterLaufAt: Long?,
        uptimeJetztMs: Long,
        bootCountJetzt: Int?,
        jetztMs: Long,
        ausdruecklicherStoppProtokolliert: Boolean,
    ): AusfallEinordnung? {
        val datenAt = letzteDatenAt?.takeIf { it > 0L } ?: return null
        val lueckeMs = jetztMs - datenAt
        if (lueckeMs <= MINDESTLUECKE_MS) return null

        val waechterLief =
            when {
                waechterLetzterLaufAt == null || waechterLetzterLaufAt <= 0L -> WaechterLief.UNBEKANNT
                waechterLetzterLaufAt in (datenAt + 1)..jetztMs -> WaechterLief.JA
                waechterLetzterLaufAt <= datenAt -> WaechterLief.NEIN
                else -> WaechterLief.UNBEKANNT
            }

        val kernelNeustart = uptimeJetztMs >= 0L && lueckeMs - uptimeJetztMs > UHR_UNSICHERHEIT_MS
        val kernelLiefDurch = uptimeJetztMs - lueckeMs > UHR_UNSICHERHEIT_MS
        val bootWechsel =
            letzterZustand?.bootCount != null &&
                bootCountJetzt != null &&
                letzterZustand.bootCount != bootCountJetzt
        val bootGleich =
            letzterZustand?.bootCount != null &&
                bootCountJetzt != null &&
                letzterZustand.bootCount == bootCountJetzt
        val akkuLeer =
            letzterZustand?.akkuProzent?.let { it <= 10 } == true &&
                letzterZustand.stromquelle == Stromzustand.Quelle.KEINE.name &&
                letzterZustand.akkuStatus != Stromzustand.Status.LAEDT.name

        val klasse =
            when {
                letzterZustand == null -> AusfallKlasse.UNBEKANNT
                letzterZustand.sauberBeendet && ausdruecklicherStoppProtokolliert -> AusfallKlasse.NUTZER_STOPP
                kernelNeustart && akkuLeer -> AusfallKlasse.AKKU_LEER
                kernelNeustart -> AusfallKlasse.KERNEL_NEUSTART
                kernelLiefDurch && bootWechsel -> AusfallKlasse.FRAMEWORK_NEUSTART
                kernelLiefDurch && bootGleich && !letzterZustand.sauberBeendet -> AusfallKlasse.STILLSTAND_GERAET_LIEF
                else -> AusfallKlasse.UNBEKANNT
            }

        return AusfallEinordnung(
            klasse = klasse,
            waechterLief = waechterLief,
            letzteDatenAt = datenAt,
            entdecktAt = jetztMs,
            belege =
                mapOf(
                    "lueckeMinuten" to lueckeMs / 60_000L,
                    "uptimeJetztMs" to uptimeJetztMs,
                    "bootCountVorher" to letzterZustand?.bootCount,
                    "bootCountJetzt" to bootCountJetzt,
                    "akkuProzentVorher" to letzterZustand?.akkuProzent,
                    "stromquelleVorher" to letzterZustand?.stromquelle,
                    "sauberBeendet" to letzterZustand?.sauberBeendet,
                    "ausdruecklicherStoppProtokolliert" to ausdruecklicherStoppProtokolliert,
                    "waechterLetzterLaufAt" to waechterLetzterLaufAt,
                    "zustandZeitpunkt" to letzterZustand?.zeitpunkt,
                ),
        )
    }
}
