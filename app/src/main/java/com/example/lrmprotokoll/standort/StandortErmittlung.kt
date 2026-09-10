package com.example.lrmprotokoll.standort

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

data class Standort(val breitengrad: Double, val laengengrad: Double)

/**
 * Ermittlung des Messorts ohne Google-Maps-API-Key oder zusaetzliche Play-Services-Abhaengigkeit
 * ("Ort ... orten lassen über Google Maps", Owner-Anfrage 10.09.2026) - [android.location.Geocoder]
 * nutzt auf Geraeten mit Play Services denselben Google-Geocoding-Dienst im Hintergrund, ohne dass
 * die App selbst einen Schluessel verwalten oder eine neue Bibliothek einbinden muss. Passt zur
 * "duenne Schicht statt SDK"-Linie dieses Projekts (siehe KDoc von
 * [com.example.lrmprotokoll.drive.DriveApiClient] und [com.example.lrmprotokoll.wetter.OpenMeteoWetterProvider]).
 *
 * Erfordert `ACCESS_COARSE_LOCATION`/`ACCESS_FINE_LOCATION` - der Aufrufer prueft/fragt die
 * Berechtigung an, bevor [aktuellerStandort] gerufen wird (analog
 * [com.example.lrmprotokoll.ui.FotoDokumentationSheet]s CAMERA-Handhabung).
 */
interface StandortErmittlung {
    /** Letzter bekannter Standort ueber alle verfuegbaren Provider - keine neue GPS-Anfrage: Fuer
     * ein Freitextfeld im Bericht reicht "zuletzt bekannt", eine frische Ortung koennte je nach
     * Umgebung mehrere Sekunden bis Minuten dauern oder ganz ausbleiben. */
    fun aktuellerStandort(): Standort?

    /** Menschenlesbare Adresse fuer [standort], `null` wenn kein Geocoder verfuegbar ist oder
     * keine Adresse gefunden wurde (z.B. offenes Meer, oder Geraet ohne Play Services). */
    suspend fun adresseFuer(standort: Standort): String?
}

class GeraeteStandortErmittlung(private val context: Context) : StandortErmittlung {

    override fun aktuellerStandort(): Standort? {
        // Explizite Pruefung statt sich auf den runCatching()-Aufrufer zu verlassen: Lint erkennt
        // eine Berechtigungspruefung nur innerhalb derselben Methode, die den Location-Aufruf
        // macht - siehe MissingPermission-Regel. Der Aufrufer (GesamtberichtStammdatenSheet)
        // prueft/fragt die Berechtigung zusaetzlich VOR dem Aufruf an (siehe Klassen-KDoc), diese
        // Pruefung hier ist die zweite, für den Compiler/Lint sichtbare Absicherung.
        val berechtigt = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (!berechtigt) return null

        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val kandidaten = listOf(
            LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER,
        )
        return kandidaten.asSequence()
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .filterNotNull()
            .maxByOrNull { it.time }
            ?.let { Standort(it.latitude, it.longitude) }
    }

    override suspend fun adresseFuer(standort: Standort): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        val geocoder = Geocoder(context, Locale.getDefault())
        val adressen = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            geocoderAsync(geocoder, standort)
        } else {
            @Suppress("DEPRECATION")
            runCatching { geocoder.getFromLocation(standort.breitengrad, standort.laengengrad, 1) }.getOrNull()
        }
        adressen?.firstOrNull()?.getAddressLine(0)
    }

    // Ohne die Annotation sieht Lint den SDK_INT-Guard in adresseFuer() nicht ueber die
    // Funktionsgrenze hinweg und meldet NewApi auf getFromLocation(..., GeocodeListener) (API 33).
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun geocoderAsync(geocoder: Geocoder, standort: Standort) =
        suspendCancellableCoroutine { fortsetzung ->
            runCatching {
                geocoder.getFromLocation(standort.breitengrad, standort.laengengrad, 1) { adressen ->
                    fortsetzung.resume(adressen)
                }
            }.onFailure { fortsetzung.resume(null) }
        }
}
