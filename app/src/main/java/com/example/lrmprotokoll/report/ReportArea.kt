package com.example.lrmprotokoll.report

/**
 * Auswahlvertrag mit laermbericht/areas.py. Keine zweite Richtwerttabelle in Kotlin.
 * Quellen und bewusst unbelegte Zuordnungen: docs/BERICHT_GEBIETSEINSTUFUNG_QUELLEN.md.
 * Bestehende unbekannte Freitexte bleiben erhalten, bis der Nutzer bewusst neu auswählt.
 */
enum class ReportArea(val label: String, val hasVerifiedLimits: Boolean) {
    WA("Allgemeines Wohngebiet", true),
    WR("Reines Wohngebiet", true),
    MI("Mischgebiet", true),
    GE("Gewerbegebiet", true),
    GI("Industriegebiet", true),
    WS("Kleinsiedlungsgebiet", false),
    WB("Besonderes Wohngebiet", false),
    MD("Dorfgebiet", false),
    MDW("Dörfliches Wohngebiet", false),
    MU("Urbanes Gebiet", false),
    MK("Kerngebiet", false);

    companion object {
        fun fromCode(value: String): ReportArea? = entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
    }
}

fun areaSelectionError(value: String): String? {
    val area = ReportArea.fromCode(value)
    return when {
        value.isBlank() -> "Bitte in den Berichtsparametern eine Gebietseinstufung auswählen."
        area == null -> "Gebietseinstufung nicht erkannt: $value. Bitte in den Berichtsparametern neu auswählen."
        !area.hasVerifiedLimits -> "Für ${area.label} (${area.name}) ist die Richtwertzuordnung noch nicht geprüft. Ein Bericht ist damit noch nicht möglich."
        else -> null
    }
}
