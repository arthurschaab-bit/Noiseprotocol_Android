package com.example.lrmprotokoll.audio

import com.example.lrmprotokoll.data.SettingsManager

/** Bereits beim Prozessstart eingeordnete Luecke an die bestehenden Diagnose-Ereignisse haengen. */
internal fun ausfallDetails(
    settings: SettingsManager,
    letzteDatenAt: Long?,
): Map<String, Any?> {
    if (letzteDatenAt == null || letzteDatenAt != settings.ausfallLetzteDatenAt) return emptyMap()
    val klasse = settings.ausfallKlassifikation ?: return emptyMap()
    return mapOf(
        "klassifikation" to klasse,
        "waechterLief" to settings.ausfallWaechterLief,
    )
}
