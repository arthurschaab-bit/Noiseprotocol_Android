package com.example.lrmprotokoll.drive.auth

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.credentials.GetCredentialResponse
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.data.SettingsManager
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Testluecken-Auftrag Stufe 5: [GoogleSignInAccessTokenProvider] war laut eigenem KDoc "NICHT in
 * einem JVM-Unit-Test pruefbar" - das galt fuer die direkte Nutzung von `CredentialManager`/
 * `Identity.getAuthorizationClient`, nicht fuer die Entscheidungslogik selbst. Mit den beiden
 * suspend-Testseams [holeCredential]/[autorisiere] laesst sich genau diese Logik pruefen: welches
 * Konto verwendet wird, wie die vier moeglichen Autorisierungsausgaenge (Token, Zustimmung noetig,
 * Fehler, weder-noch) auf [Result] abgebildet werden, und dass die Anfrage an Google korrekt
 * befuellt wird (drive.file-Scope, richtiges Konto).
 *
 * Robolectric nur wegen [SettingsManager] (echte SharedPreferences) - die eigentliche Logik
 * braucht keine Android-Klassen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GoogleSignInAccessTokenProviderTest {

    private lateinit var context: Context
    private lateinit var settings: SettingsManager

    private fun googleIdCredentialAntwort(id: String, displayName: String? = null): GetCredentialResponse {
        val credential = GoogleIdTokenCredential.Builder()
            .setId(id)
            .setIdToken("irrelevant-fuer-diesen-test")
            .apply { displayName?.let { setDisplayName(it) } }
            .build()
        return GetCredentialResponse(credential)
    }

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent("com.example.lrmprotokoll.TEST_ZUSTIMMUNG"), PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * [AuthorizationResult] hat keine Builder-API - die Konstruktor-Parameterreihenfolge ist per
     * javap-Bytecode-Analyse verifiziert (play-services-auth 21.3.0): Position 2 ist
     * `accessToken` (Position 1 ist `serverAuthCode`, ungenutzt hier), Position 6 ist
     * `pendingIntent` (treibt `hasResolution()`).
     */
    private fun autorisierungsErgebnis(
        accessToken: String? = null,
        pendingIntent: PendingIntent? = null,
    ): AuthorizationResult = AuthorizationResult(null, accessToken, null, emptyList(), null, pendingIntent)

    @Before
    fun aufbauen() {
        context = ApplicationProvider.getApplicationContext()
        settings = SettingsManager(context)
    }

    @Test
    fun erfolgreicherAblaufOhneGespeichertesKontoErmitteltEsUndAutorisiert() = runTest {
        var kontoAnfrageAufrufe = 0
        val provider = GoogleSignInAccessTokenProvider(
            context, settings,
            holeCredential = { _, _ -> kontoAnfrageAufrufe++; googleIdCredentialAntwort("owner@example.com", "Owner") },
            autorisiere = { _, _ -> autorisierungsErgebnis(accessToken = "frisches-token") },
        )

        val ergebnis = provider.holeToken()

        assertTrue(ergebnis.isSuccess)
        assertEquals("frisches-token", ergebnis.getOrNull())
        assertEquals(1, kontoAnfrageAufrufe)
        assertEquals(
            "Die ermittelte E-Mail muss fuer kuenftige Hintergrund-Zyklen gespeichert werden",
            "owner@example.com", settings.googleAccountEmail,
        )
        assertEquals("Owner", settings.googleAccountName)
    }

    @Test
    fun gespeichertesKontoWirdWiederverwendetOhneErneuteInteraktiveAnfrage() = runTest {
        settings.googleAccountEmail = "gespeichert@example.com"
        var kontoAnfrageAufrufe = 0
        val provider = GoogleSignInAccessTokenProvider(
            context, settings,
            holeCredential = { _, _ -> kontoAnfrageAufrufe++; googleIdCredentialAntwort("sollte-nicht-verwendet-werden") },
            autorisiere = { _, _ -> autorisierungsErgebnis(accessToken = "token") },
        )

        provider.holeToken()

        assertEquals(
            "Ein Hintergrund-Sync-Zyklus darf keinen sichtbaren UI-Flow ausloesen, solange ein " +
                "Konto bereits bekannt ist (siehe Klassen-KDoc)",
            0, kontoAnfrageAufrufe,
        )
    }

    @Test
    fun zustimmungNoetigLiefertResultFailureMitDemPendingIntent() = runTest {
        val pi = pendingIntent()
        settings.googleAccountEmail = "owner@example.com"
        val provider = GoogleSignInAccessTokenProvider(
            context, settings,
            autorisiere = { _, _ -> autorisierungsErgebnis(pendingIntent = pi) },
        )

        val ergebnis = provider.holeToken()

        assertTrue(ergebnis.isFailure)
        val fehler = ergebnis.exceptionOrNull()
        assertTrue(
            "hasResolution()==true (Pending-Intent gesetzt) ohne Token muss die spezielle " +
                "Zustimmungs-Ausnahme auslösen, nicht die generische",
            fehler is AutorisierungBenoetigtException,
        )
        assertEquals(pi.intentSender, (fehler as AutorisierungBenoetigtException).intentSender)
    }

    @Test
    fun wederTokenNochAufloesungLiefertEineGenerischeFehlermeldung() = runTest {
        settings.googleAccountEmail = "owner@example.com"
        val provider = GoogleSignInAccessTokenProvider(
            context, settings,
            autorisiere = { _, _ -> autorisierungsErgebnis() },
        )

        val ergebnis = provider.holeToken()

        assertTrue(ergebnis.isFailure)
        assertTrue(ergebnis.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun netzwerkfehlerBeimAutorisierenWirdAlsFehlschlagDurchgereicht() = runTest {
        settings.googleAccountEmail = "owner@example.com"
        val netzfehler = IOException("kein Netz")
        val provider = GoogleSignInAccessTokenProvider(
            context, settings,
            autorisiere = { _, _ -> throw netzfehler },
        )

        val ergebnis = provider.holeToken()

        assertTrue(ergebnis.isFailure)
        assertEquals(netzfehler, ergebnis.exceptionOrNull())
    }

    @Test
    fun fehlerBeimErmittelnDesKontosWirdEbenfallsDurchgereicht() = runTest {
        val kontoFehler = IllegalStateException("kein Google-Konto auf dem Geraet")
        val provider = GoogleSignInAccessTokenProvider(
            context, settings,
            holeCredential = { _, _ -> throw kontoFehler },
        )

        val ergebnis = provider.holeToken()

        assertTrue(ergebnis.isFailure)
        assertEquals(kontoFehler, ergebnis.exceptionOrNull())
    }

    @Test
    fun autorisierungsanfrageEnthaeltDenDriveFileScopeUndDasRichtigeKonto() = runTest {
        settings.googleAccountEmail = "owner@example.com"
        var gesehenerScope: List<Scope>? = null
        var gesehenesKonto: String? = null
        val provider = GoogleSignInAccessTokenProvider(
            context, settings,
            autorisiere = { _, anfrage ->
                gesehenerScope = anfrage.requestedScopes
                gesehenesKonto = anfrage.account?.name
                autorisierungsErgebnis(accessToken = "token")
            },
        )

        provider.holeToken()

        assertEquals(listOf(Scope(GoogleClientConfig.DRIVE_FILE_SCOPE)), gesehenerScope)
        assertEquals("owner@example.com", gesehenesKonto)
    }

    @Test
    fun abmeldenSetztAlleDriveBezogenenEinstellungenZurueck() {
        settings.googleAccountEmail = "owner@example.com"
        settings.googleAccountName = "Owner"
        settings.driveFolderId = "ordner-id"
        settings.driveSyncEnabled = true
        settings.driveOrdnerBlockiert = true
        val provider = GoogleSignInAccessTokenProvider(context, settings)

        provider.abmelden()

        assertNull(settings.googleAccountEmail)
        assertNull(settings.googleAccountName)
        assertNull(settings.driveFolderId)
        assertFalse(settings.driveSyncEnabled)
        assertFalse(settings.driveOrdnerBlockiert)
    }
}
