package com.example.lrmprotokoll.drive

import java.io.ByteArrayOutputStream
import java.io.File
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
import okio.BufferedSink
import okio.buffer
import okio.source
import org.json.JSONArray
import org.json.JSONObject

private const val BASIS_URL = "https://www.googleapis.com"

/**
 * Blockgroesse des resumable Uploads. Drive verlangt ein Vielfaches von 256 KiB fuer alle
 * Bloecke ausser dem letzten; 8 MiB ist der von Google empfohlene Mittelweg zwischen
 * Anzahl der Roundtrips und dem, was bei einem Abbruch neu uebertragen werden muss.
 */
private const val BLOCKGROESSE = 8L * 1024 * 1024
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

    override suspend fun dateiHochladenResumable(
        name: String,
        ordnerId: String,
        datei: File,
        mimeType: String,
        fortsetzenAb: String?,
        sessionGestartet: suspend (String) -> Unit,
        fortschritt: suspend (Long, Long) -> Unit,
    ): Result<String> = mitToken { token ->
        val gesamt = datei.length()

        var sessionUri = fortsetzenAb
        var bestaetigt = 0L

        if (sessionUri != null) {
            when (val stand = frageServerstandAb(sessionUri, token, gesamt)) {
                is Serverstand.Fertig -> return@mitToken stand.fileId
                is Serverstand.Offen -> bestaetigt = stand.bestaetigt
                // Ein Session-URI ist rund eine Woche gueltig. Ist er weg, ist der einzige
                // sinnvolle Weg ein Neuanfang - genau EINMAL, nicht in einer Schleife.
                Serverstand.Verfallen -> sessionUri = null
            }
        }

        if (sessionUri == null) {
            sessionUri = starteUploadSession(name, ordnerId, mimeType, token)
            bestaetigt = 0L
            // Vor dem ersten Datenblock, nicht danach: Stirbt der Prozess zwischen Sitzungsstart
            // und erstem Block, soll der naechste Versuch diese Sitzung fortsetzen koennen.
            sessionGestartet(sessionUri)
        }

        // Eine leere Datei hat keinen Block zu senden; Drive schliesst sie ueber die
        // Nullbytes-Anfrage ab. Ohne diesen Zweig liefe die Schleife unten nie durch.
        if (gesamt == 0L) {
            val antwort = sendeBlock(sessionUri, token, datei, von = 0L, laenge = 0L, gesamt = 0L, mimeType = mimeType)
            return@mitToken leseDateiId(antwort)
        }

        while (bestaetigt < gesamt) {
            val laenge = minOf(BLOCKGROESSE, gesamt - bestaetigt)
            val antwort = sendeBlock(sessionUri, token, datei, von = bestaetigt, laenge = laenge, gesamt = gesamt, mimeType = mimeType)
            when (antwort.code) {
                200, 201 -> {
                    fortschritt(gesamt, gesamt)
                    return@mitToken leseDateiId(antwort)
                }
                // 308 ist zwischen den Bloecken der Normalfall, kein Fehler.
                308 -> {
                    // Nach dem Serverstand richten, nicht nach dem, was gesendet zu sein
                    // scheint: Ein teilweise angekommener Block wuerde sonst als vollstaendig
                    // gelten und ein Loch in der Datei hinterlassen.
                    val neuBestaetigt = bytesAusRange(antwort.range)
                    if (neuBestaetigt == null || neuBestaetigt <= bestaetigt) {
                        throw DriveApiException(
                            "Upload kommt nicht voran (Range: ${antwort.range ?: "fehlt"})",
                            httpCode = 308,
                        )
                    }
                    bestaetigt = neuBestaetigt
                    fortschritt(bestaetigt, gesamt)
                }
                else -> throw DriveApiException("Drive antwortete mit HTTP ${antwort.code}", httpCode = antwort.code)
            }
        }

        // Alle Bytes bestaetigt, aber kein 200/201 gesehen - der Serverstand entscheidet.
        when (val stand = frageServerstandAb(sessionUri, token, gesamt)) {
            is Serverstand.Fertig -> stand.fileId
            else -> throw DriveApiException("Upload endete ohne Datei-ID")
        }
    }

    /**
     * Ergebnis der Serverstand-Abfrage - ein leeres PUT, dessen Content-Range statt eines
     * Bereichs nur ein Sternchen traegt und damit fragt: "Wie viel hast du wirklich?"
     */
    private sealed interface Serverstand {
        data class Fertig(val fileId: String) : Serverstand
        data class Offen(val bestaetigt: Long) : Serverstand
        data object Verfallen : Serverstand
    }

    private suspend fun frageServerstandAb(sessionUri: String, token: String, gesamt: Long): Serverstand {
        val request = Request.Builder()
            .url(sessionUri)
            .put(ByteArray(0).toRequestBody(null))
            .header("Authorization", "Bearer $token")
            .header("Content-Range", "bytes */$gesamt")
            .build()

        val antwort = fuehreAusRoh(request)
        return when (antwort.code) {
            200, 201 -> Serverstand.Fertig(leseDateiId(antwort))
            308 -> Serverstand.Offen(bytesAusRange(antwort.range) ?: 0L)
            404 -> Serverstand.Verfallen
            else -> throw DriveApiException("Drive antwortete mit HTTP ${antwort.code}", httpCode = antwort.code)
        }
    }

    private suspend fun starteUploadSession(name: String, ordnerId: String, mimeType: String, token: String): String {
        val metadaten = JSONObject().apply {
            put("name", name)
            put("parents", JSONArray().put(ordnerId))
        }.toString().toRequestBody(JSON)

        val url = "$basisUrl/upload/drive/v3/files".toHttpUrl().newBuilder()
            .addQueryParameter("uploadType", "resumable")
            .build()

        val request = Request.Builder()
            .url(url)
            .post(metadaten)
            .header("Authorization", "Bearer $token")
            .header("X-Upload-Content-Type", mimeType)
            .build()

        val antwort = fuehreAusRoh(request)
        if (antwort.code !in 200..299) {
            throw DriveApiException("Upload-Sitzung abgelehnt (HTTP ${antwort.code})", httpCode = antwort.code)
        }
        return antwort.location
            ?: throw DriveApiException("Upload-Sitzung ohne Location-Header")
    }

    private suspend fun sendeBlock(
        sessionUri: String,
        token: String,
        datei: File,
        von: Long,
        laenge: Long,
        gesamt: Long,
        mimeType: String,
    ): RohAntwort {
        val bis = if (gesamt == 0L) 0L else von + laenge - 1
        val range = if (gesamt == 0L) "bytes */0" else "bytes $von-$bis/$gesamt"

        val request = Request.Builder()
            .url(sessionUri)
            .put(DateiAbschnitt(datei, von, laenge, mimeType))
            .header("Authorization", "Bearer $token")
            .header("Content-Range", range)
            .build()

        return fuehreAusRoh(request)
    }

    /**
     * Streamt genau [laenge] Bytes ab [von] aus [datei] - ohne die Datei zu lesen, bevor sie
     * gebraucht wird. `readBytes()` waere hier genau der Fehler, den dieser ganze Weg vermeidet.
     */
    private class DateiAbschnitt(
        private val datei: File,
        private val von: Long,
        private val laenge: Long,
        private val mimeType: String,
    ) : okhttp3.RequestBody() {
        override fun contentType() = mimeType.toMediaType()
        override fun contentLength(): Long = laenge
        override fun writeTo(sink: BufferedSink) {
            datei.inputStream().use { strom ->
                var uebersprungen = 0L
                while (uebersprungen < von) {
                    val n = strom.skip(von - uebersprungen)
                    // skip() darf 0 liefern, ohne dass das Dateiende erreicht ist - eine
                    // Endlosschleife waere hier die schlimmere Antwort als ein Fehler.
                    if (n <= 0) throw java.io.IOException("Konnte nicht bis Byte $von springen")
                    uebersprungen += n
                }
                strom.source().buffer().use { quelle ->
                    sink.write(quelle, laenge)
                }
            }
        }
    }

    /** Liest die bestaetigten Bytes aus einem `Range: bytes=0-8388607`-Header. */
    private fun bytesAusRange(range: String?): Long? {
        val ende = range?.substringAfterLast('-')?.toLongOrNull() ?: return null
        return ende + 1
    }

    private fun leseDateiId(antwort: RohAntwort): String =
        runCatching { JSONObject(antwort.rumpf).getString("id") }
            .getOrElse { throw DriveApiException("Antwort ohne Datei-ID", httpCode = antwort.code, cause = it) }

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

    /**
     * Rohe Antwort inklusive Statuscode und der beiden Header, die der resumable Upload braucht.
     *
     * Warum nicht [fuehreAus]: Dort ist jeder Nicht-2xx-Code eine Exception. Beim resumable
     * Upload ist `308 Resume Incomplete` aber der **Normalfall** zwischen zwei Bloecken, und
     * `404` auf den Session-URI ist eine Anweisung ("fang von vorn an"), kein Abbruchgrund.
     */
    private class RohAntwort(val code: Int, val rumpf: String, val location: String?, val range: String?)

    private suspend fun fuehreAusRoh(request: Request): RohAntwort =
        suspendCancellableCoroutine { fortsetzung ->
            val call = client.newCall(request)
            fortsetzung.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    fortsetzung.resumeWithException(DriveApiException("Drive nicht erreichbar", cause = e))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        fortsetzung.resumeWith(
                            Result.success(
                                RohAntwort(
                                    code = it.code,
                                    rumpf = it.body?.string().orEmpty(),
                                    location = it.header("Location"),
                                    range = it.header("Range"),
                                )
                            )
                        )
                    }
                }
            })
        }
}

/** Escaped Anfuehrungszeichen und Backslashes fuer die Drive-Query-Syntax (`name = '...'`). */
internal fun escapeFuerDriveQuery(text: String): String =
    text.replace("\\", "\\\\").replace("'", "\\'")
