package com.example.lrmprotokoll.ui

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentSender
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.drive.auth.AutorisierungBenoetigtException
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Owner-Rückmeldung nach Geraetetest: "Mit Google verbinden" scheiterte dauerhaft mit
 * "Autorisierung ohne Zugriffstoken - erneute Zustimmung nötig", weil die App den
 * Zustimmungsbildschirm nie anzeigte, den Google fuer diesen Fall verlangt (siehe
 * [AutorisierungBenoetigtException]-KDoc). [findeAutorisierungBenoetigt] ist der Teil davon, der
 * die Ursachenkette danach durchsucht - Robolectric noetig, weil ein echtes
 * [android.content.IntentSender] fuer den Konstruktor gebraucht wird.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FindeAutorisierungBenoetigtTest {

    private fun testIntentSender(): IntentSender {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return PendingIntent.getActivity(
            context, 0, Intent("test-action"), PendingIntent.FLAG_IMMUTABLE
        ).intentSender
    }

    @Test
    fun findetDieExceptionDirekt() {
        val exception = AutorisierungBenoetigtException(testIntentSender())
        assertSame(exception, findeAutorisierungBenoetigt(exception))
    }

    @Test
    fun findetSieAmEndeEinerUrsachenkette() {
        // Entspricht dem echten Pfad: GoogleDriveApiClient.mitToken() verpackt sie bereits einmal
        // in eine DriveApiException, bevor sie beim Aufrufer ankommt.
        val exception = AutorisierungBenoetigtException(testIntentSender())
        val verpackt = RuntimeException("Kein Zugriffstoken verfügbar", exception)

        assertSame(exception, findeAutorisierungBenoetigt(verpackt))
    }

    @Test
    fun liefertNullOhneSolcheUrsacheInDerKette() {
        val fehler = RuntimeException("Kein Zugriffstoken verfügbar", IllegalStateException("anderer Grund"))
        assertNull(findeAutorisierungBenoetigt(fehler))
    }

    @Test
    fun liefertNullFuerNull() {
        assertNull(findeAutorisierungBenoetigt(null))
    }
}
