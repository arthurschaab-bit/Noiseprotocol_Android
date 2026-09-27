package com.example.lrmprotokoll.report

import androidx.annotation.StringRes
import com.example.lrmprotokoll.R
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

data class BerichtVoraussetzungFehler(
    @StringRes val textRes: Int,
    val args: List<String> = emptyList(),
)

data class BerichtVoraussetzung(
    val id: BerichtVoraussetzungId,
    val fehler: BerichtVoraussetzungFehler?,
) {
    val erfuellt: Boolean get() = fehler == null
}

private fun fehler(
    @StringRes textRes: Int,
    vararg args: String,
): BerichtVoraussetzungFehler = BerichtVoraussetzungFehler(textRes, args.toList())

/** Dieselben fachlichen Schranken wie beim Export; Texte werden erst in der UI lokalisiert. */
fun pruefeBerichtVoraussetzungen(
    zeitraum: BerichtZeitraum?,
    tage: List<BerichtTag>,
    config: ReportConfigEntity?,
    ausgewaehlteIds: Map<LocalDate, Long>,
): List<BerichtVoraussetzung> {
    val verdichteteTage = tage.filter { it.verdichteteMinuten > 0 }.joinToString(", ") { it.label }
    val offeneAuswahl =
        tage
            .filter { it.stammdatenKandidaten.size > 1 && gewaehlteStammdaten(it, ausgewaehlteIds) == null }
            .joinToString(", ") { it.label }
    val gebiet = config?.gebietseinstufung
    val gebietsArt = gebiet?.let { ReportArea.fromCode(it) }
    return listOf(
        BerichtVoraussetzung(
            BerichtVoraussetzungId.ZEITRAUM,
            if (zeitraum == null) fehler(R.string.report_precondition_error_range) else null,
        ),
        BerichtVoraussetzung(
            BerichtVoraussetzungId.ROHDATEN,
            if (zeitraum != null && tage.none { it.rohwerte > 0 }) {
                fehler(R.string.report_precondition_error_raw_data)
            } else {
                null
            },
        ),
        BerichtVoraussetzung(
            BerichtVoraussetzungId.RETENTION,
            verdichteteTage
                .takeIf { it.isNotEmpty() }
                ?.let { fehler(R.string.report_precondition_error_retention, it) },
        ),
        BerichtVoraussetzung(
            BerichtVoraussetzungId.BEWERTUNG,
            when {
                config == null -> fehler(R.string.report_precondition_error_loading)
                tage.any { it.unbestaetigteWerte > 0 } && !config.erzwingeBerichtOhneBestaetigteBewertung ->
                    fehler(R.string.report_precondition_error_rating)
                else -> null
            },
        ),
        BerichtVoraussetzung(
            BerichtVoraussetzungId.STAMMDATEN_AUSWAHL,
            offeneAuswahl
                .takeIf { it.isNotEmpty() }
                ?.let { fehler(R.string.report_precondition_error_selection, it) },
        ),
        BerichtVoraussetzung(
            BerichtVoraussetzungId.GEBIET,
            when {
                gebiet == null -> fehler(R.string.report_precondition_error_loading)
                gebiet.isBlank() -> fehler(R.string.report_precondition_error_area_missing)
                gebietsArt == null -> fehler(R.string.report_precondition_error_area_unknown, gebiet)
                !gebietsArt.hasVerifiedLimits -> fehler(R.string.report_precondition_error_area_unverified, gebietsArt.name)
                else -> null
            },
        ),
    )
}
