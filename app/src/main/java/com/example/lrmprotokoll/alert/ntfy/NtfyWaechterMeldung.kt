package com.example.lrmprotokoll.alert.ntfy

import com.example.lrmprotokoll.data.SettingsManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val TEXT_UTF8 = "text/plain; charset=utf-8".toMediaType()

/**
 * Schmale Hilfsmethode fuer Fernmeldungen des Aufzeichnungs-Waechters per ntfy (Owner-Entscheidung E1).
 *
 * Verwendet denselben Server, dasselbe Topic und dieselbe Header-Behandlung ([alsHeaderWert])
 * wie [NtfyAlertChannel], aber mit niedriger Prioritaet (3 = kein Weckton/Alarmton) und ohne
 * das Alarm-Zustandsmodell oder Datenbankschreiben.
 */
suspend fun sendeNtfyWaechterMeldung(
    settings: SettingsManager,
    nachricht: String,
    client: OkHttpClient = OkHttpClient(),
    titel: String = "Lärmprotokoll",
    prioritaet: String = "3",
    tags: String = "warning",
): Result<Unit> {
    if (!settings.ntfyAktiv) return Result.success(Unit)
    val server = settings.ntfyServer.trimEnd('/')
    val topic = settings.ntfyTopic
    if (server.isBlank() || topic.isBlank()) return Result.success(Unit)

    val request =
        Request.Builder()
            .url("$server/$topic")
            .post(nachricht.toRequestBody(TEXT_UTF8))
            .header("Title", alsHeaderWert(titel))
            .header("Priority", prioritaet)
            .header("Tags", tags)
            .build()

    return runCatching { client.fuehreAus(request) }
}
