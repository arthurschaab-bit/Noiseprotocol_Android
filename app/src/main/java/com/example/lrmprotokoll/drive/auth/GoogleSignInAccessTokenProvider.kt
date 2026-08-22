package com.example.lrmprotokoll.drive.auth

import android.accounts.Account
import android.content.Context
import android.content.IntentSender
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.example.lrmprotokoll.data.SettingsManager
import com.example.lrmprotokoll.drive.AccessTokenProvider
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Signalisiert, dass Google einen expliziten Zustimmungsbildschirm verlangt, bevor
 * [GoogleSignInAccessTokenProvider] ein Zugriffstoken bekommen kann (Identity Services
 * "Authorization API": `AuthorizationResult.hasResolution() == true`) - typischerweise beim
 * allerersten Autorisieren des drive.file-Scopes oder wenn eine vorherige Zustimmung widerrufen
 * wurde.
 *
 * [intentSender] muss ueber `ActivityResultContracts.StartIntentSenderForResult` in einer
 * Activity/Compose-UI gestartet werden (siehe `SettingsScreen`); nach erfolgreicher Zustimmung
 * liefert ein erneuter [GoogleSignInAccessTokenProvider.holeToken]-Aufruf direkt ein Token.
 */
class AutorisierungBenoetigtException(val intentSender: IntentSender) :
    Exception("Zustimmung des Nutzers erforderlich, um Drive-Zugriff zu autorisieren")

/**
 * Holt vor jedem Upload ein frisches Drive-Zugriffstoken - nutzt gespeicherte Kontodaten
 * aus [settings], um Hintergrund-Synchronisationen und Wiederverbindungen ohne wiederholte
 * interaktive Konto-Auswahldialoge auszuführen.
 *
 * ⚠ NICHT lauffaehig ohne echte [GoogleClientConfig.SERVER_CLIENT_ID] und NICHT in einem
 * JVM-Unit-Test pruefbar: `CredentialManager` und `Identity.getAuthorizationClient` brauchen
 * echte Play Services auf einem echten Geraet.
 */
class GoogleSignInAccessTokenProvider(
    private val context: Context,
    private val settings: SettingsManager? = null,
) : AccessTokenProvider {

    override suspend fun holeToken(): Result<String> = runCatching {
        check(GoogleClientConfig.konfiguriert) {
            "Keine echte OAuth-Client-ID eingerichtet - siehe GoogleClientConfig"
        }

        val kontoEmail = settings?.googleAccountEmail?.takeIf { it.isNotBlank() }
            ?: ermittleAngemeldetesKonto()
        autorisiereDriveZugriff(kontoEmail)
    }

    /**
     * Interaktiver Login-Schritt aus der Benutzeroberfläche:
     * Fordert über CredentialManager das Google-Konto an, speichert E-Mail und Anzeigename
     * in [settings] und holt die Autorisierung für drive.file ein.
     */
    suspend fun meldeAnInteraktiv(): Result<String> = runCatching {
        check(GoogleClientConfig.konfiguriert) {
            "Keine echte OAuth-Client-ID eingerichtet - siehe GoogleClientConfig"
        }
        val kontoEmail = ermittleAngemeldetesKonto()
        autorisiereDriveZugriff(kontoEmail)
    }

    /**
     * Trennt das Google-Konto und setzt alle Drive-Zustände zurück.
     */
    fun abmelden() {
        settings?.googleAccountEmail = null
        settings?.googleAccountName = null
        settings?.driveFolderId = null
        settings?.driveSyncEnabled = false
        settings?.driveSyncLastMessage = null
        settings?.driveOrdnerBlockiert = false
    }

    /**
     * Liest oder erfragt das Google-Konto über CredentialManager.
     */
    private suspend fun ermittleAngemeldetesKonto(): String {
        val anfrage = GetCredentialRequest.Builder()
            .addCredentialOption(
                GetSignInWithGoogleOption.Builder(GoogleClientConfig.SERVER_CLIENT_ID)
                    .setNonce(null)
                    .build()
            )
            .build()

        val antwort = CredentialManager.create(context).getCredential(context, anfrage)
        val zugangsdaten = GoogleIdTokenCredential.createFrom(antwort.credential.data)
        val email = zugangsdaten.id
        settings?.googleAccountEmail = email
        settings?.googleAccountName = zugangsdaten.displayName
        return email
    }

    private suspend fun autorisiereDriveZugriff(kontoEmail: String): String {
        val anfrageBuilder = AuthorizationRequest.Builder()
            .setRequestedScopes(listOf(Scope(GoogleClientConfig.DRIVE_FILE_SCOPE)))

        if (kontoEmail.isNotBlank()) {
            anfrageBuilder.setAccount(Account(kontoEmail, "com.google"))
        }
        val anfrage = anfrageBuilder.build()

        val client = Identity.getAuthorizationClient(context)
        return suspendCancellableCoroutine { fortsetzung ->
            client.authorize(anfrage)
                .addOnSuccessListener { ergebnis ->
                    val token = ergebnis.accessToken
                    val pendingIntent = ergebnis.pendingIntent
                    when {
                        token != null -> fortsetzung.resume(token)
                        ergebnis.hasResolution() && pendingIntent != null ->
                            fortsetzung.resumeWithException(
                                AutorisierungBenoetigtException(pendingIntent.intentSender)
                            )
                        else -> fortsetzung.resumeWithException(
                            IllegalStateException("Autorisierung ohne Zugriffstoken - erneute Zustimmung nötig")
                        )
                    }
                }
                .addOnFailureListener { fehler -> fortsetzung.resumeWithException(fehler) }
        }
    }
}
