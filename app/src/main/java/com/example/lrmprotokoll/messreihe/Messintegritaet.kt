package com.example.lrmprotokoll.messreihe

import com.example.lrmprotokoll.data.ReportConfigEntity

enum class Messintegritaet {
    VOLLSTAENDIG,
    EINGESCHRAENKT,
    LUECKENHAFT,
    ;

    fun anzeigetext(): String =
        when (this) {
            VOLLSTAENDIG -> "Vollständig"
            EINGESCHRAENKT -> "Eingeschränkt"
            LUECKENHAFT -> "Lückenhaft"
        }
}

data class Integritaetsbefund(
    val stufe: Messintegritaet,
    val verfuegbarkeitProzent: Double,
    val ausfaelle: Int,
    val ausfallDauerMs: Long,
    val rohdatenVerdichtet: Boolean,
    val unbestaetigteWerte: Int,
    val gapAnzahl: Int = 0,
)

/**
 * Bewertet die Messintegrität einer Messreihe anhand der Datenverfügbarkeit,
 * Verbindungsausfälle, GAP-Flags und Verdichtungszustand gemäß UX-Audit Kapitel 20.2 / F-12.
 *
 * - VOLLSTAENDIG: Verfügbarkeit >= tierSchwelleVollmessungProzent (Default 90%)
 *   UND gapAnzahl == 0 UND nicht rohdatenVerdichtet
 * - EINGESCHRAENKT: Verfügbarkeit >= tierSchwelleTeilerfassungProzent (Default 70%)
 *   ODER einzelne GAP-Zeilen (bei Verfügbarkeit >= tierSchwelleTeilerfassungProzent)
 *   UND nicht rohdatenVerdichtet
 * - LUECKENHAFT: Verfügbarkeit < tierSchwelleTeilerfassungProzent ODER rohdatenVerdichtet
 */
fun bewerteMessintegritaet(
    von: Long,
    bis: Long,
    ausfallbaender: List<Ausfallband>,
    gapAnzahl: Int = 0,
    verdichteteMinuten: Int = 0,
    unbestaetigteWerte: Int = 0,
    config: ReportConfigEntity = ReportConfigEntity(),
): Integritaetsbefund {
    val verfuegbarkeitProzent = berechneDatenverfuegbarkeitProzent(von, bis, ausfallbaender)
    val ausfaelle =
        ausfallbaender.count { band ->
            val bandVon = band.von.coerceIn(von, bis)
            val bandBis = (band.bis ?: bis).coerceIn(von, bis)
            bandBis > bandVon
        }
    val ausfallDauerMs =
        ausfallbaender.sumOf { band ->
            val bandVon = band.von.coerceIn(von, bis)
            val bandBis = (band.bis ?: bis).coerceIn(von, bis)
            (bandBis - bandVon).coerceAtLeast(0)
        }
    val rohdatenVerdichtet = verdichteteMinuten > 0

    val stufe =
        when {
            rohdatenVerdichtet -> Messintegritaet.LUECKENHAFT
            verfuegbarkeitProzent < config.tierSchwelleTeilerfassungProzent -> Messintegritaet.LUECKENHAFT
            verfuegbarkeitProzent >= config.tierSchwelleVollmessungProzent && gapAnzahl == 0 -> Messintegritaet.VOLLSTAENDIG
            else -> Messintegritaet.EINGESCHRAENKT
        }

    return Integritaetsbefund(
        stufe = stufe,
        verfuegbarkeitProzent = verfuegbarkeitProzent,
        ausfaelle = ausfaelle,
        ausfallDauerMs = ausfallDauerMs,
        rohdatenVerdichtet = rohdatenVerdichtet,
        unbestaetigteWerte = unbestaetigteWerte,
        gapAnzahl = gapAnzahl,
    )
}
