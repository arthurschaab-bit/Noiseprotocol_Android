package com.example.lrmprotokoll.report

import com.example.lrmprotokoll.data.ReportConfigEntity
import java.time.LocalDate

enum class BerichtVoraussetzungId {
    ZEITRAUM,
    ROHDATEN,
    RETENTION,
    BEWERTUNG,
    STAMMDATEN_AUSWAHL,
    GEBIET,
}

data class BerichtVoraussetzung(
    val id: BerichtVoraussetzungId,
    val fehler: String?,
) {
    val erfuellt: Boolean get() = fehler == null
}

/** Dieselben fachlichen Schranken wie beim Export, ohne UI- oder Datenbankzustand. */
fun pruefeBerichtVoraussetzungen(
    zeitraum: BerichtZeitraum?,
    tage: List<BerichtTag>,
    config: ReportConfigEntity?,
    ausgewaehlteIds: Map<LocalDate, Long>,
): List<BerichtVoraussetzung> =
    listOf(
        BerichtVoraussetzung(
            BerichtVoraussetzungId.ZEITRAUM,
            if (zeitraum == null) "Bitte zuerst einen Datumsbereich wählen." else null,
        ),
        BerichtVoraussetzung(
            BerichtVoraussetzungId.ROHDATEN,
            if (zeitraum != null && tage.none { it.rohwerte > 0 }) {
                "Im gewählten Zeitraum liegen keine Rohdaten für einen Bericht vor."
            } else {
                null
            },
        ),
        BerichtVoraussetzung(BerichtVoraussetzungId.RETENTION, retentionFehler(tage)),
        BerichtVoraussetzung(BerichtVoraussetzungId.BEWERTUNG, config?.let { bewertungsFehler(tage, it) }),
        BerichtVoraussetzung(BerichtVoraussetzungId.STAMMDATEN_AUSWAHL, auswahlFehler(tage, ausgewaehlteIds)),
        BerichtVoraussetzung(BerichtVoraussetzungId.GEBIET, config?.let { areaSelectionError(it.gebietseinstufung) }),
    )
