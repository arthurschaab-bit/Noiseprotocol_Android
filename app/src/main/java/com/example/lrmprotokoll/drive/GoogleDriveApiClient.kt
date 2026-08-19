package com.example.lrmprotokoll.drive

import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.GZIPOutputStream
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

private const val BASIS_URL = "https://www.googleapis.com"
private val JSON = "application/json; charset=utf-8".toMediaType()

/**
 * Echte Anbindung an die Drive-v3-REST-API. Siehe [DriveApiClient] fuer die Begruendung, warum
 * kein vollstaendiges SDK verwendet wird.
 *
 * ⚠ Nicht gegen den echten Drive-Server verifiziert - hier ist kein Netzzugang zu
 * `googleapis.com` verfuegbar. Die Endpunkte und der `gzip`-Weg (`Content-Encoding: gzip` auf
 * dem Media-Upload) folgen der oeffentlich dokumentierten Drive-v3-API, sind aber nicht live
 * getestet. Gehoert in die Geraete-Endabnahme, siehe PR.
 */
class GoogleDriveApiClient(
    private val tokenProvider: AccessTokenProvider,
    private val client: OkHttpClient,
    private val basisUrl: String = BASIS_URL,
) : DriveApiClient {

    override suspend fun ordnerAnlegen(name: String): Result<String> = mitToken { token ->
        val body = JSONObject().apply {
            put("name", name)
            put("mimeType", "application/vnd.google-apps.folder")
        }.toString().toRequestBody(JSON)

        val request = Request.Builder()
            .url("$basisUrl/drive/v3/files")
            .post(body)
            .header("Authorization", "Bearer $token")
            .build()

        val antwort = fuehreAus(request)
        JSONObject(antwort).getString("id")
    }

    override suspend fun dateiSuchen(name: String, ordnerId: String): Result<DriveDatei?> = mitToken { token ->
        // Query-Parameter ueber HttpUrl.Builder statt Hand-Encoding - Drive-Query-Syntax
        // erlaubt Anfuehrungszeichen im Namen, die escaped werden muessen.
        val query = "name = '${escapeFuerDriveQuery(name)}' and '$ordnerId' in parents and trashed = false"
        val url = "$basisUrl/drive/v3/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("fields", "files(id,name)")
            .build()

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $token")
            .build()

        val antwort = fuehreAus(request)
        val dateien: JSONArray = JSONObject(antwort).getJSONArray("files")
        if (dateien.length() == 0) {
            null
        } else {
            val erste = dateien.getJSONObject(0)
            DriveDatei(id = erste.getString("id"), name = erste.getString("name"))
        }
    }

    override suspend fun dateiAnlegen(
        name: String,
        ordnerId: String,
        inhalt: ByteArray,
        mimeType: String,
        gzip: Boolean,
    ): Result<String> = mitToken { token ->
        val metadaten = JSONObject().apply {
            put("name", name)
            put("parents", JSONArray().put(ordnerId))
        }.toString()

        val (uebertrageneBytes, contentEncoding) = kodiere(inhalt, gzip)

        val multipart = MultipartBody.Builder(grenze())
            .setType("multipart/related".toMediaType())
            .addPart(metadaten.toRequestBody(JSON))
            .addPart(uebertrageneBytes.toRequestBody(mimeType.toMediaType()))
            .build()

        val requestBuilder = Request.Builder()
            .url("$basisUrl/upload/drive/v3/files?uploadType=multipart")
            .post(multipart)
            .header("Authorization", "Bearer $token")
        contentEncoding?.let { requestBuilder.header("Content-Encoding", it) }

        val antwort = fuehreAus(requestBuilder.build())
        JSONObject(antwort).getString("id")
    }

    override suspend fun dateiAktualisieren(
        fileId: String,
        inhalt: ByteArray,
        mimeType: String,
        gzip: Boolean,
    ): Result<Unit> = mitToken { token ->
        val (uebertrageneBytes, contentEncoding) = kodiere(inhalt, gzip)

        val requestBuilder = Request.Builder()
            .url("$basisUrl/upload/drive/v3/files/$fileId?uploadType=media")
            .patch(uebertrageneBytes.toRequestBody(mimeType.toMediaType()))
            .header("Authorization", "Bearer $token")
        contentEncoding?.let { requestBuilder.header("Content-Encoding", it) }

        fuehreAus(requestBuilder.build())
        Unit
    }

    private fun kodiere(inhalt: ByteArray, gzip: Boolean): Pair<ByteArray, String?> {
        if (!gzip) return inhalt to null
        val ausgabe = ByteArrayOutputStream()
        GZIPOutputStream(ausgabe).use { it.write(inhalt) }
        return ausgabe.toByteArray() to "gzip"
    }

    private fun grenze(): String = "drive-sync-${UUID.randomUUID()}"

    private suspend fun <T> mitToken(block: suspend (String) -> T): Result<T> {
        val token = tokenProvider.holeToken().getOrElse {
            return Result.failure(DriveApiException("Kein Zugriffstoken verfügbar", cause = it))
        }
        return runCatching { block(token) }
    }

    /**
     * Wartet auf die Antwort, ohne einen Thread zu blockieren (Muster wie
     * [com.example.lrmprotokoll.alert.ntfy.NtfyAlertChannel]). Wirft [DriveApiException] mit
     * dem HTTP-Code bei einer Fehlerantwort, damit der Aufrufer 401/403/404 unterscheiden kann.
     */
    private suspend fun fuehreAus(request: Request): String =
        suspendCancellableCoroutine { fortsetzung ->
            val call = client.newCall(request)
            fortsetzung.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    fortsetzung.resumeWithException(DriveApiException("Drive nicht erreichbar", cause = e))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val rumpf = it.body?.string().orEmpty()
                        if (it.isSuccessful) {
                            fortsetzung.resumeWith(Result.success(rumpf))
                        } else {
                            fortsetzung.resumeWithException(
                                DriveApiException("Drive antwortete mit HTTP ${it.code}", httpCode = it.code)
                            )
                        }
                    }
                }
            })
        }
}

/** Escaped Anfuehrungszeichen und Backslashes fuer die Drive-Query-Syntax (`name = '...'`). */
internal fun escapeFuerDriveQuery(text: String): String =
    text.replace("\\", "\\\\").replace("'", "\\'")
