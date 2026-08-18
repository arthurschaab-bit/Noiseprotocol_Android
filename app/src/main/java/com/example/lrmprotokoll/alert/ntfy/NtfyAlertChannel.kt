package com.example.lrmprotokoll.alert.ntfy

import com.example.lrmprotokoll.alert.Alert
import com.example.lrmprotokoll.alert.AlertChannel
import com.example.lrmprotokoll.alert.AlertMessages
import com.example.lrmprotokoll.alert.ChannelId
import com.example.lrmprotokoll.data.SettingsManager
import java.io.IOException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

private val TEXT_UTF8 = "text/plain; charset=utf-8".toMediaType()

/**
 * Push auf das Zweitgeraet ueber ntfy (Plan 7.4, Option A): ein einziger HTTP-POST, kein SDK,
 * kein Firebase-Projekt, keine Kontoverwaltung. Auf dem Zweitgeraet reicht die ntfy-App mit
 * abonniertem Topic.
 *
 * **Priority 5** durchbricht "Nicht stoeren". Ein Alarm, der nachts um drei lautlos ankommt, ist
 * wertlos - das ist der Grund fuer den Header, nicht Bequemlichkeit.
 *
 * **Zum Topic:** Beim oeffentlichen Server ist der Topic-Name die einzige Zugangskontrolle. Er
 * wird deshalb nie protokolliert, auch nicht gekuerzt und auch nicht im Fehlerfall - eine
 * Fehlermeldung mit der vollen URL darin waere genau das Leck, gegen das die 32 Zufallszeichen
 * gedacht sind. Aus demselben Grund enthaelt der Alarmtext keine Messwerte und keine Orte
 * ([AlertMessages]).
 */
class NtfyAlertChannel(
    private val settings: SettingsManager,
    private val client: OkHttpClient = OkHttpClient(),
) : AlertChannel {

    override val id: ChannelId = ChannelId.NTFY

    override val isAvailable: Boolean
        get() = settings.ntfyAktiv &&
            settings.ntfyTopic.isNotBlank() &&
            settings.ntfyServer.isNotBlank()

    override suspend fun send(alert: Alert): Result<Unit> {
        val server = settings.ntfyServer.trimEnd('/')
        val topic = settings.ntfyTopic
        if (topic.isBlank()) {
            return Result.failure(IllegalStateException("Kein ntfy-Topic eingerichtet"))
        }

        val request = Request.Builder()
            .url("$server/$topic")
            .post(alert.message.toRequestBody(TEXT_UTF8))
            .header("Title", alsHeaderWert(AlertMessages.titel(alert.kind)))
            .header("Priority", prioritaet(alert))
            .header("Tags", tags(alert))
            .build()

        return runCatching { client.fuehreAus(request) }
    }

    /**
     * Die Entwarnung kommt bewusst mit niedriger Prioritaet: Sie soll ankommen, aber niemanden
     * nachts wecken - der Ausfall ist zu diesem Zeitpunkt ja vorbei.
     */
    private fun prioritaet(alert: Alert): String = when (alert.kind) {
        com.example.lrmprotokoll.alert.AlertKind.RESOLVED -> "3"
        com.example.lrmprotokoll.alert.AlertKind.TEST -> "3"
        else -> "5"
    }

    private fun tags(alert: Alert): String = when (alert.kind) {
        com.example.lrmprotokoll.alert.AlertKind.RESOLVED -> "white_check_mark"
        com.example.lrmprotokoll.alert.AlertKind.TEST -> "gear"
        else -> "warning"
    }
}

/**
 * HTTP-Header duerfen nur ASCII enthalten (RFC 7230). "Lärmprotokoll" enthaelt ein ä, und OkHttp
 * lehnt das mit einer IllegalArgumentException ab - der Versand waere also bei JEDEM Alarm an
 * dieser Zeile gescheitert, nicht nur in Randfaellen. Aufgefallen ist es erst, weil der Test
 * gegen einen echten HTTP-Server laeuft; gegen einen gefakten Client waere es unbemerkt bis auf
 * das Geraet gekommen.
 *
 * Nicht-ASCII wird deshalb als RFC-2047-Encoded-Word uebertragen. Sollte ein Server das nicht
 * dekodieren, steht im Titel die kodierte Zeichenkette - haesslich, aber der Alarm kommt an und
 * der eigentliche Text steht ohnehin im Rumpf, der als UTF-8 uebertragen wird. Ein Fehlerfall,
 * der die Zustellung kostet, waere hier deutlich schlimmer als ein haesslicher Titel.
 */
internal fun alsHeaderWert(text: String): String {
    if (text.all { it.code in 32..126 }) return text
    val base64 = android.util.Base64.encodeToString(
        text.toByteArray(Charsets.UTF_8),
        android.util.Base64.NO_WRAP,
    )
    return "=?UTF-8?B?$base64?="
}

/**
 * Wartet auf die Antwort, ohne einen Thread zu blockieren, und bricht den Aufruf ab, wenn die
 * umgebende Coroutine abgebrochen wird. Ein haengender Alarmversand darf den Koordinator nicht
 * blockieren.
 */
private suspend fun OkHttpClient.fuehreAus(request: Request) =
    suspendCancellableCoroutine { fortsetzung ->
        val call = newCall(request)
        fortsetzung.invokeOnCancellation { runCatching { call.cancel() } }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Die Ausnahme von OkHttp traegt die vollstaendige URL und damit das Topic im
                // Text. Sie wird deshalb nicht durchgereicht, sondern ersetzt.
                fortsetzung.resumeWithException(IOException("ntfy nicht erreichbar"))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) {
                        fortsetzung.resumeWith(Result.success(Unit))
                    } else {
                        fortsetzung.resumeWithException(IOException("ntfy antwortete mit HTTP ${it.code}"))
                    }
                }
            }
        })
    }
