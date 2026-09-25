package com.example.lrmprotokoll.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Der Default von [SettingsManager.onboardingCompleted] (UX-Audit F-14, Owner-Entscheidung
 * 25.09.2026).
 *
 * Vorher stand hier fest `true`: Die vierseitige Einfuehrung erschien damit bei einer
 * Neuinstallation nie von selbst. Ein fester Default `false` waere das andere Extrem gewesen -
 * dann saehe jede Bestandsinstallation die Einfuehrung beim naechsten Update einmalig. Getestet
 * wird deshalb genau die Unterscheidung, nicht "ein Setter setzt".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsManagerOnboardingTest {
    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun settings() = SettingsManager(context())

    /** Direkt in die Datei schreiben, ohne [SettingsManager] - so entsteht exakt der Zustand
     * einer Installation, die schon vor diesem Fix lief. */
    private fun schreibeAltenSchluessel(
        schluessel: String,
        wert: String,
    ) {
        context()
            .getSharedPreferences("noise_settings", Context.MODE_PRIVATE)
            .edit()
            .putString(schluessel, wert)
            .commit()
    }

    @Test
    fun frischeInstallationHatDieEinfuehrungNochNichtGesehen() {
        // Leere Einstellungsdatei = Neuinstallation: die Einfuehrung soll erscheinen.
        assertFalse(settings().onboardingCompleted)
    }

    @Test
    fun bestandsinstallationBekommtDieEinfuehrungNichtNachgereicht() {
        // Irgendein beliebiger frueherer Eintrag genuegt als Beleg, dass die App hier lief.
        schreibeAltenSchluessel("audio_trigger_quelle", "AUTO")

        assertTrue(settings().onboardingCompleted)
    }

    @Test
    fun abgeschlosseneEinfuehrungBleibtAbgeschlossen() {
        val settings = settings()
        settings.onboardingCompleted = true

        assertTrue(settings().onboardingCompleted)
    }

    @Test
    fun ausdruecklichesZuruecksetzenZeigtDieEinfuehrungErneut() {
        // "Einfuehrung erneut anzeigen" in den Einstellungen muss auch dann greifen, wenn sonst
        // schon Werte in der Datei stehen - sonst wuerde istBestandsinstallation() den
        // ausdruecklichen Wunsch ueberstimmen.
        schreibeAltenSchluessel("audio_trigger_quelle", "AUTO")
        val settings = settings()
        settings.onboardingCompleted = false

        assertFalse(settings().onboardingCompleted)
    }

    @Test
    fun derOnboardingSchluesselAlleinMachtNochKeineBestandsinstallation() {
        // Grenzfall der Heuristik: Steht NUR der Onboarding-Schluessel selbst in der Datei,
        // darf er nicht als "hier lief schon etwas" zaehlen - sonst wuerde der gespeicherte
        // Wert `false` durch den Default `true` verdeckt.
        context()
            .getSharedPreferences("noise_settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("onboarding_completed", false)
            .commit()

        assertFalse(settings().onboardingCompleted)
    }
}
