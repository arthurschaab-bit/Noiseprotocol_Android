package com.example.lrmprotokoll.audio

import com.example.lrmprotokoll.data.SettingsManager
import com.example.lrmprotokoll.diagnose.LebenszyklusRingFile

/** Die Zustandsdatei kann beim Start hoechstens ein Lebenszeichen (15 min) hinter der DB liegen. */
private fun gehoertZurEingeordnetenLuecke(
    settings: SettingsManager,
    letzteDatenAt: Long?,
): Boolean {
    val referenz = settings.ausfallLetzteDatenAt
    if (referenz <= 0L || letzteDatenAt == null) return false
    val abstand = letzteDatenAt - referenz
    val prozessStartAt = settings.letzterProzessStartAt
    return abstand in 0L..15 * 60_000L && (prozessStartAt <= 0L || letzteDatenAt <= prozessStartAt)
}

/** Der erste Waechterlauf nach dem Prozessstart gehoert noch zur offenen Datenluecke. */
internal fun korrigiereWaechterLauf(
    settings: SettingsManager,
    ringFile: LebenszyklusRingFile?,
    letzteDatenAt: Long?,
    waechterAt: Long,
) {
    if (!gehoertZurEingeordnetenLuecke(settings, letzteDatenAt) ||
        settings.ausfallKlassifikation == null ||
        waechterAt <= (letzteDatenAt ?: Long.MAX_VALUE) ||
        settings.ausfallWaechterLief == "ja"
    ) {
        return
    }

    settings.ausfallWaechterLief = "ja"
    ringFile?.protokolliere(
        ereignis = "Ausfall-Waechterspur korrigiert",
        details =
            mapOf(
                "letzteDatenAt" to settings.ausfallLetzteDatenAt,
                "letzteDatenAtDatenbank" to letzteDatenAt,
                "waechterLief" to "ja",
                "waechterLetzterLaufAt" to waechterAt,
            ),
        timestampMillis = waechterAt,
    )
}

/** Bereits beim Prozessstart eingeordnete Luecke an die bestehenden Diagnose-Ereignisse haengen. */
internal fun ausfallDetails(
    settings: SettingsManager,
    letzteDatenAt: Long?,
): Map<String, Any?> {
    if (!gehoertZurEingeordnetenLuecke(settings, letzteDatenAt)) return emptyMap()
    val klasse = settings.ausfallKlassifikation ?: return emptyMap()
    return mapOf(
        "klassifikation" to klasse,
        "waechterLief" to settings.ausfallWaechterLief,
    )
}
