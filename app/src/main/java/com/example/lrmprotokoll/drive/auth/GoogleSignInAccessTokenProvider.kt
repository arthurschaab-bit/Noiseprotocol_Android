package com.example.lrmprotokoll.drive.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
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
 * Holt vor jedem Upload ein frisches Drive-Zugriffstoken - bewusst kein eigenes Caching, siehe
 * [AccessTokenProvider] (Plan 8.4.3: "wird nicht selbst persistiert, sondern vor jedem Upload
 * ueber authorize() erneuert - das laeuft still, solange die Zustimmung besteht").
 *
 * ⚠ NICHT lauffaehig ohne echte [GoogleClientConfig.SERVER_CLIENT_ID] und NICHT in einem
 * JVM-Unit-Test pruefbar: `CredentialManager` und `Identity.getAuthorizationClient` brauchen
 * echte Play Services auf einem echten Geraet. Diese Klasse gehoert vollstaendig in die
 * Geraete-Endabnahme (siehe PR). Die Alarm-/Sync-Logik kennt nur die [AccessTokenProvider]-
 * Schnittstelle und ist davon unberuehrt.
 */
class GoogleSignInAccessTokenProvider(private val context: Context) : AccessTokenProvider {

    override suspend fun holeToken(): Result<String> = runCatching {
        check(GoogleClientConfig.konfiguriert) {
            "Keine echte OAuth-Client-ID eingerichtet - siehe GoogleClientConfig"
        }

        val angemeldetesKonto = ermittleAngemeldetesKonto()
        autorisiereDriveZugriff(angemeldetesKonto)
    }

    /**
     * Liest das zuletzt ueber [meldeAn] angemeldete Konto. Erwartet, dass die Anmeldung selbst
     * bereits einmalig ueber die Einstellungen ("Mit Google verbinden") stattgefunden hat -
     * [holeToken] meldet nicht selbst neu an, weil das einen sichtbaren UI-Flow braucht, den ein
     * Hintergrund-Sync-Zyklus nicht ausloesen darf.
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
        return zugangsdaten.id
    }

    private suspend fun autorisiereDriveZugriff(kontoEmail: String): String {
        val anfrage = AuthorizationRequest.Builder()
            .setRequestedScopes(listOf(Scope(GoogleClientConfig.DRIVE_FILE_SCOPE)))
            .build()

        val client = Identity.getAuthorizationClient(context)
        return suspendCancellableCoroutine { fortsetzung ->
            client.authorize(anfrage)
                .addOnSuccessListener { ergebnis ->
                    val token = ergebnis.accessToken
                    if (token != null) {
                        fortsetzung.resume(token)
                    } else {
                        fortsetzung.resumeWithException(
                            IllegalStateException("Autorisierung ohne Zugriffstoken - erneute Zustimmung nötig")
                        )
                    }
                }
                .addOnFailureListener { fehler -> fortsetzung.resumeWithException(fehler) }
        }
    }
}
