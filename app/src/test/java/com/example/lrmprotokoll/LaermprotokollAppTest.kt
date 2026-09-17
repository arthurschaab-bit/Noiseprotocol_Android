package com.example.lrmprotokoll

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Schritt 1 Test 1 (Konzept 4.1, Akzeptanzkriterium "Der zweite Prozess baut nachweislich
 * keinen AppContainer auf"): ACRA startet seinen Sender in einem eigenen Prozess (":acra"),
 * darin darf Application.onCreate() NIE einen zweiten AppContainer aufbauen.
 *
 * [ACRA.isACRASenderServiceProcess] liest den echten Betriebssystem-Prozessnamen - in einem
 * Robolectric-Test laeuft alles im selben Prozess, das laesst sich nicht faken. Deshalb hier
 * eine eigene, manuell durch attachBaseContext()/onCreate() getriebene Instanz statt der von
 * Robolectric automatisch gebauten Singleton-Applikation (die jeder andere Test im Modul ueber
 * ApplicationProvider.getApplicationContext<LaermprotokollApp>() bekommt und die bereits mit
 * dem echten - im Test immer false liefernden - Prozess-Check durchgelaufen ist).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaermprotokollAppTest {

    @Test
    fun acraSenderProzessBautKeinenAppContainerAuf() {
        val app = LaermprotokollApp()
        app.acraSenderProcessOverride = true

        app.attachBaseContext(ApplicationProvider.getApplicationContext())
        app.onCreate()

        assertFalse(
            "Im ACRA-Sender-Prozess darf niemals ein zweiter AppContainer entstehen (Konzept 4.1)",
            app.isContainerInitialized(),
        )
    }

    @Test
    fun normalerProzessBautDenAppContainerWieBisherAuf() {
        val app = LaermprotokollApp()
        app.acraSenderProcessOverride = false

        app.attachBaseContext(ApplicationProvider.getApplicationContext())
        app.onCreate()

        assertTrue(app.isContainerInitialized())
    }
}
