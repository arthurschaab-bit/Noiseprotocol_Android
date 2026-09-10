package com.example.lrmprotokoll.wetter

import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

/** Aktuelle Wetterlage fuer die Randbedingungen-Zeile eines Gesamtberichts. */
data class Wetterlage(
    val temperaturCelsius: Double,
    val windgeschwindigkeitKmh: Double,
    val beschreibung: String,
) {
    /** Kurzform fuer das Stammdaten-Feld "Wetter", z.B. "12 °C, bedeckt, Wind 8 km/h". */
    fun alsKurztext(): String =
        "%.0f °C, %s, Wind %.0f km/h".format(
            Locale.getDefault(), temperaturCelsius, beschreibung, windgeschwindigkeitKmh,
        )
}

/**
 * Aktuelle Wetterlage fuer Koordinaten - "sinnvolles Interface" (Owner-Anfrage 10.09.2026)
 * statt eines rein manuellen Freitextfelds. Eine eigene Abstraktion statt eines direkten
 * OkHttp-Aufrufs im Stammdaten-Dialog, analog zu
 * [com.example.lrmprotokoll.drive.DriveApiClient]: austauschbar gegen einen Fake in Tests, ohne
 * echtes Netzwerk. Das Wetterfeld im Bericht bleibt trotzdem ein editierbares Textfeld - ein
 * fehlgeschlagener Abruf (kein Netz, Dienst nicht erreichbar) blockiert nichts, siehe
 * [aktuelleWetterlage]s `Result`.
 */
interface WetterProvider {
    suspend fun aktuelleWetterlage(breitengrad: Double, laengengrad: Double): Result<Wetterlage>
}

/**
 * Open-Meteo (open-meteo.com) statt eines Google-/OpenWeather-SDKs: kostenlos, kein API-Key,
 * keine Kontoverwaltung - passt zur "duenne Schicht statt SDK"-Linie dieses Projekts (siehe
 * KDoc von [com.example.lrmprotokoll.drive.DriveApiClient]). [basisUrl] ist injizierbar, damit
 * sich der Client wie [com.example.lrmprotokoll.alert.ntfy.NtfyAlertChannel] gegen
 * `MockWebServer` testen laesst, ohne echtes Netzwerk.
 */
class OpenMeteoWetterProvider(
    private val client: OkHttpClient = OkHttpClient(),
    private val basisUrl: String = "https://api.open-meteo.com/v1/forecast",
) : WetterProvider {

    override suspend fun aktuelleWetterlage(breitengrad: Double, laengengrad: Double): Result<Wetterlage> {
        val url = basisUrl.toHttpUrl().newBuilder()
            .addQueryParameter("latitude", breitengrad.toString())
            .addQueryParameter("longitude", laengengrad.toString())
            .addQueryParameter("current", "temperature_2m,wind_speed_10m,weather_code")
            .build()
        val request = Request.Builder().url(url).build()
        return runCatching { parseAntwort(client.fuehreAus(request)) }
    }

    internal fun parseAntwort(json: String): Wetterlage {
        val current = JSONObject(json).getJSONObject("current")
        val code = current.getInt("weather_code")
        return Wetterlage(
            temperaturCelsius = current.getDouble("temperature_2m"),
            windgeschwindigkeitKmh = current.getDouble("wind_speed_10m"),
            beschreibung = WMO_BESCHREIBUNGEN[code] ?: "unbekannte Wetterlage",
        )
    }

    companion object {
        /**
         * WMO-Wettercodes (siehe open-meteo.com/en/docs, Abschnitt "WMO Weather interpretation
         * codes") auf eine kurze deutsche Beschreibung reduziert - fuer die Berichtszeile reicht
         * das, eine vollstaendige 1:1-Übersetzung aller ~30 Codes waere hier Overkill.
         */
        private val WMO_BESCHREIBUNGEN = mapOf(
            0 to "klar", 1 to "überwiegend klar", 2 to "teilweise bewölkt", 3 to "bedeckt",
            45 to "Nebel", 48 to "Nebel mit Reifablagerung",
            51 to "leichter Nieselregen", 53 to "Nieselregen", 55 to "starker Nieselregen",
            61 to "leichter Regen", 63 to "Regen", 65 to "starker Regen",
            71 to "leichter Schneefall", 73 to "Schneefall", 75 to "starker Schneefall",
            80 to "leichte Regenschauer", 81 to "Regenschauer", 82 to "starke Regenschauer",
            95 to "Gewitter", 96 to "Gewitter mit Hagel", 99 to "starkes Gewitter mit Hagel",
        )
    }
}

/** Wie [com.example.lrmprotokoll.alert.ntfy.fuehreAus] - eigene Kopie statt geteilter Nutzung,
 * weil beide Aufrufer sonst voneinander abhingen, ohne inhaltlich etwas zu teilen. */
private suspend fun OkHttpClient.fuehreAus(request: Request): String =
    suspendCancellableCoroutine { fortsetzung ->
        val call = newCall(request)
        fortsetzung.invokeOnCancellation { runCatching { call.cancel() } }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                fortsetzung.resumeWithException(IOException("Wetterdienst nicht erreichbar", e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) {
                        fortsetzung.resumeWith(Result.success(it.body?.string() ?: ""))
                    } else {
                        fortsetzung.resumeWithException(IOException("Wetterdienst antwortete mit HTTP ${it.code}"))
                    }
                }
            }
        })
    }
