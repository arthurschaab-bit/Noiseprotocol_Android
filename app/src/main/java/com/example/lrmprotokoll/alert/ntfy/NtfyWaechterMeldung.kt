package com.example.lrmprotokoll.alert.ntfy

import com.example.lrmprotokoll.data.SettingsManager
import okhttp3.OkHttpClient

/**
 * Schmale Hilfsmethode fuer Fernmeldungen des Aufzeichnungs-Waechters per ntfy (Owner-Entscheidung E1).
 *
 * Verwendet denselben Server, dasselbe Topic und dieselbe Header-Behandlung ([alsHeaderWert])
 * wie [NtfyAlertChannel], aber mit niedriger Prioritaet (3 = kein Weckton/Alarmton) und ohne
 * das Alarm-Zustandsmodell oder Datenbankschreiben. Berücksichtigt den Hauptschalter
 * [SettingsManager.alarmierungAktiv].
 */
suspend fun sendeNtfyWaechterMeldung(
    settings: SettingsManager,
    nachricht: String,
    client: OkHttpClient,
    titel: String = "Lärmprotokoll",
    prioritaet: String = "3",
    tags: String = "warning",
): Result<Unit> {
    if (!settings.alarmierungAktiv) return Result.success(Unit)
    return sendeNtfyNachricht(
        settings = settings,
        text = nachricht,
        titel = titel,
        prioritaet = prioritaet,
        tags = tags,
        client = client,
    )
}

