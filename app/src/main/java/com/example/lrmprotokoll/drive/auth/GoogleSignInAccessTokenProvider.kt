package com.example.lrmprotokoll.drive.auth

import android.accounts.Account
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.IntentSender
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import com.example.lrmprotokoll.data.SettingsManager
import com.example.lrmprotokoll.drive.AccessTokenProvider
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Löst bei Bedarf eine Activity aus einem Context oder ContextWrapper auf.
 * CredentialManager benötigt zwingend einen Activity-basierten Context, um die Konto-Auswahl
 * (Bottom Sheet) anzuzeigen.
 */
fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

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
 * ⚠ NICHT lauffaehig ohne echte [GoogleClientConfig.SERVER_CLIENT_ID]. Die eigentliche
 * Entscheidungslogik (welches Konto, welcher Fehlerfall, wann [AutorisierungBenoetigtException])
 * IST per JVM-Unit-Test pruefbar - siehe `GoogleSignInAccessTokenProviderTest`, das
 * [holeCredential]/[autorisiere] durch Fakes ersetzt. Was dabei NICHT geprueft wird: ob die
 * echten Play-Services-Implementierungen dahinter tatsaechlich so funktionieren wie angenommen -
 * das bleibt Sache des Geraetetests.
 */
class GoogleSignInAccessTokenProvider(
    private val context: Context,
    private val settings: SettingsManager? = null,
    /** Testluecken-Auftrag Stufe 5: als suspend-Funktion statt als injizierter
     * `CredentialManager` - so bleibt der Konstruktor testbar, ohne das Interface (mehrere
     * ungenutzte Async-Methoden) nachbilden zu muessen. Default ruft den echten CredentialManager
     * auf. */
    private val holeCredential: suspend (Context, GetCredentialRequest) -> GetCredentialResponse =
        { ctx, anfrage -> CredentialManager.create(ctx).getCredential(ctx, anfrage) },
    /** Testluecken-Auftrag Stufe 5: ebenfalls als suspend-Funktion statt als injizierter
     * `AuthorizationClient` - dessen `HasApiKey<...>`-Basis verlangt einen internen, von aussen
     * nicht konstruierbaren `ApiKey`-Typ und liesse sich ohne Mocking-Framework gar nicht fake
     * implementieren (dieses Projekt verwendet bewusst nur handgeschriebene Fakes, siehe die
     * uebrigen Tests). Default kapselt den Task-basierten Identity-Services-Aufruf wie zuvor. */
    private val autorisiere: suspend (Context, AuthorizationRequest) -> AuthorizationResult =
        { ctx, anfrage ->
            suspendCancellableCoroutine { fortsetzung ->
                Identity.getAuthorizationClient(ctx).authorize(anfrage)
                    .addOnSuccessListener { fortsetzung.resume(it) }
                    .addOnFailureListener { fehler -> fortsetzung.resumeWithException(fehler) }
            }
        },
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
     *
     * [uiContext] sollte der UI-/Activity-Context (z. B. aus `LocalContext.current`) sein,
     * damit das Bottom-Sheet der Kontoauswahl gestartet werden kann.
     */
    suspend fun meldeAnInteraktiv(uiContext: Context? = null): Result<String> = runCatching {
        check(GoogleClientConfig.konfiguriert) {
            "Keine echte OAuth-Client-ID eingerichtet - siehe GoogleClientConfig"
        }
        val kontoEmail = ermittleAngemeldetesKonto(uiContext)
        autorisiereDriveZugriff(kontoEmail, uiContext)
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
    private suspend fun ermittleAngemeldetesKonto(uiContext: Context? = null): String {
        val targetContext = uiContext?.findActivity() ?: uiContext ?: context.findActivity() ?: context
        val anfrage = GetCredentialRequest.Builder()
            .addCredentialOption(
                GetSignInWithGoogleOption.Builder(GoogleClientConfig.SERVER_CLIENT_ID)
                    .setNonce(null)
                    .build()
            )
            .build()

        val antwort = holeCredential(targetContext, anfrage)
        val zugangsdaten = GoogleIdTokenCredential.createFrom(antwort.credential.data)
        val email = zugangsdaten.id
        settings?.googleAccountEmail = email
        settings?.googleAccountName = zugangsdaten.displayName
        return email
    }

    private suspend fun autorisiereDriveZugriff(kontoEmail: String, uiContext: Context? = null): String {
        val targetContext = uiContext?.findActivity() ?: uiContext ?: context.findActivity() ?: context
        val anfrageBuilder = AuthorizationRequest.Builder()
            .setRequestedScopes(listOf(Scope(GoogleClientConfig.DRIVE_FILE_SCOPE)))

        if (kontoEmail.isNotBlank()) {
            anfrageBuilder.setAccount(Account(kontoEmail, "com.google"))
        }
        val anfrage = anfrageBuilder.build()

        val ergebnis = autorisiere(targetContext, anfrage)
        val token = ergebnis.accessToken
        val pendingIntent = ergebnis.pendingIntent
        return when {
            token != null -> token
            ergebnis.hasResolution() && pendingIntent != null ->
                throw AutorisierungBenoetigtException(pendingIntent.intentSender)
            else -> throw IllegalStateException("Autorisierung ohne Zugriffstoken - erneute Zustimmung nötig")
        }
    }
}
