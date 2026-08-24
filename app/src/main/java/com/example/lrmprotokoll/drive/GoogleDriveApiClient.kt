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

    override suspend fun ordnerSuchen(name: String): Result<DriveDatei?> = mitToken { token ->
        val query = "name = '${escapeFuerDriveQuery(name)}' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
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

    override suspend fun ordnerAuflisten(): Result<List<DriveDatei>> = mitToken { token ->
        val query = "mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        val url = "$basisUrl/drive/v3/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("fields", "files(id,name)")
            .addQueryParameter("pageSize", "100")
            .build()

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $token")
            .build()

        val antwort = fuehreAus(request)
        val dateien: JSONArray = JSONObject(antwort).getJSONArray("files")
        val liste = mutableListOf<DriveDatei>()
        for (i in 0 until dateien.length()) {
            val obj = dateien.getJSONObject(i)
            liste.add(DriveDatei(id = obj.getString("id"), name = obj.getString("name")))
        }
        liste
    }

    override suspend fun ordnerUmbenennen(ordnerId: String, neuerName: String): Result<Unit> = mitToken { token ->
        val body = JSONObject().apply {
            put("name", neuerName)
        }.toString().toRequestBody(JSON)

        val request = Request.Builder()
            .url("$basisUrl/drive/v3/files/$ordnerId")
            .patch(body)
            .header("Authorization", "Bearer $token")
            .build()

        fuehreAus(request)
        Unit
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

    override suspend fun dateienInOrdnerAuflisten(ordnerId: String): Result<Set<String>> = mitToken { token ->
        val query = "'$ordnerId' in parents and trashed = false"
        val url = "$basisUrl/drive/v3/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("fields", "files(name)")
            .addQueryParameter("pageSize", "1000")
            .build()

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $token")
            .build()

        val antwort = fuehreAus(request)
        val dateien: JSONArray = JSONObject(antwort).getJSONArray("files")
        val namen = mutableSetOf<String>()
        for (i in 0 until dateien.length()) {
            namen.add(dateien.getJSONObject(i).getString("name"))
        }
        namen
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

        val multipart = MultipartBody.Builder(grenze())
            .setType("multipart/related".toMediaType())
            .addPart(metadaten.toRequestBody(JSON))
            .addPart(inhalt.toRequestBody(mimeType.toMediaType()))
            .build()

        // Content-Encoding gilt fuer den GESAMTEN Rumpf. Nur den Dateianteil zu gzippen und den
        // Header trotzdem fuer das komplette Multipart-Paket zu setzen, waere KEIN gueltiges
        // gzip-Dokument mehr - die Grenzen und die JSON-Metadaten blieben unkomprimiert
        // dazwischen stehen. Deshalb erst das vollstaendige Multipart-Paket in Bytes wandeln und
        // ERST DANACH als Ganzes komprimieren (nachgewiesen durch Test: Roundtrip-Entpacken des
        // Rumpfs ergibt wieder gueltigen Multipart-Inhalt).
        val vollstaendigerRumpf = okio.Buffer().also { multipart.writeTo(it) }.readByteArray()
        val (uebertrageneBytes, contentEncoding) = kodiere(vollstaendigerRumpf, gzip)

        val requestBuilder = Request.Builder()
            .url("$basisUrl/upload/drive/v3/files?uploadType=multipart")
            .post(uebertrageneBytes.toRequestBody(multipart.contentType()))
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
