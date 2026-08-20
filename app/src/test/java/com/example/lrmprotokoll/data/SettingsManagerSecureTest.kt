package com.example.lrmprotokoll.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Plan Abschnitt 6 (M6): ntfyTopic/ntfyServer/heartbeatUrl muessen verschluesselt liegen, nicht
 * im Klartext-`noise_settings` - mit Fallback, falls der Keystore auf einem Geraet nicht
 * verfuegbar ist.
 *
 * WICHTIG: Robolectric/die JVM stellen den Provider "AndroidKeyStore" grundsaetzlich nicht
 * bereit (`KeyStoreException: AndroidKeyStore not found`, selbst geprueft - kein Software-Ersatz
 * vorhanden). Der Konstruktor von [SettingsManager] macht [SettingsManager]s zweiten Parameter
 * deshalb injizierbar: die uebrigen Tests hier pruefen die Migrations-/Orchestrierungslogik
 * gegen ein einfaches, unverschluesseltes Test-SharedPreferences als Stellvertreter fuer die
 * echte verschluesselte Ablage - das deckt "verhaelt sich die Weiche richtig", nicht "ist auf der
 * Festplatte tatsaechlich verschluesselt". Letzteres ist nur am echten Geraet pruefbar (siehe
 * docs/PROMPT_M6.md). [echterKeystoreIstUnterRobolectricNichtVerfuegbarFallbackFunktioniertTrotzdem]
 * verifiziert stattdessen exakt das reale Verhalten in dieser Testumgebung: der dokumentierte
 * Klartext-Fallback greift und die Einstellung bleibt trotzdem benutzbar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsManagerSecureTest {

    private fun klartextPrefs(context: Context) =
        context.getSharedPreferences("noise_settings", Context.MODE_PRIVATE)

    private fun testSecurePrefs(context: Context, name: String) =
        context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Test
    fun echterKeystoreIstUnterRobolectricNichtVerfuegbarFallbackFunktioniertTrotzdem() {
        // Bewusst OHNE injizierten securePrefs-Ersatz: das ist der reale Produktionspfad, der in
        // dieser Testumgebung auf den Klartext-Fallback zurueckfaellt (siehe Klassendoc). Der
        // Test dokumentiert dieses Verhalten, statt es stillschweigend zu ignorieren.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsManager(context)

        settings.ntfyTopic = "FallbackTopic"

        assertEquals("FallbackTopic", settings.ntfyTopic)
        assertEquals(
            "Ohne verfuegbaren Keystore muss der Fallback im Klartext-Speicher landen",
            "FallbackTopic",
            klartextPrefs(context).getString("ntfy_topic", null),
        )
    }

    @Test
    fun ntfyTopicLandetNichtImKlartextSpeicherWennSichereAblageVerfuegbarIst() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsManager(context, testSecurePrefs(context, "sicher_1"))

        settings.ntfyTopic = "GeheimesTopicDasNiemandLesenSoll"

        assertNull(
            "ntfyTopic darf nicht im Klartext-SharedPreferences landen, wenn eine sichere Ablage da ist",
            klartextPrefs(context).getString("ntfy_topic", null),
        )
    }

    @Test
    fun geschriebenerWertKommtUnveraendertZurueck() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsManager(context, testSecurePrefs(context, "sicher_2"))

        settings.ntfyTopic = "TopicA1"
        settings.ntfyServer = "https://eigene.instanz.example"
        settings.heartbeatUrl = "https://hc-ping.com/irgendeine-uuid"

        assertEquals("TopicA1", settings.ntfyTopic)
        assertEquals("https://eigene.instanz.example", settings.ntfyServer)
        assertEquals("https://hc-ping.com/irgendeine-uuid", settings.heartbeatUrl)
    }

    @Test
    fun wertUeberlebtEineNeueSettingsManagerInstanzMitDerselbenSicherenAblage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sicher = testSecurePrefs(context, "sicher_3")
        SettingsManager(context, sicher).ntfyTopic = "UeberlebtNeustart42"

        val zweiteInstanz = SettingsManager(context, sicher)
        assertEquals("UeberlebtNeustart42", zweiteInstanz.ntfyTopic)
    }

    @Test
    fun bestehenderKlartextwertWirdBeimErstenLesenMigriertUndEntfernt() {
        // Bestandsinstallationen von vor M6 haben ntfy_topic im Klartext-SharedPreferences
        // stehen - die Migration darf den Wert weder verlieren noch im Klartext liegen lassen.
        val context = ApplicationProvider.getApplicationContext<Context>()
        klartextPrefs(context).edit().putString("ntfy_topic", "AltesKlartextTopicVorM6").apply()
        val sicher = testSecurePrefs(context, "sicher_4")

        val settings = SettingsManager(context, sicher)
        assertEquals("AltesKlartextTopicVorM6", settings.ntfyTopic)
        assertNull(
            "Der migrierte Klartextwert haette entfernt werden muessen",
            klartextPrefs(context).getString("ntfy_topic", null),
        )

        // Und der migrierte Wert bleibt auch fuer eine weitere Instanz sichtbar - die Migration
        // darf nicht nur "durchgereicht", sondern muss tatsaechlich persistiert worden sein.
        assertEquals("AltesKlartextTopicVorM6", SettingsManager(context, sicher).ntfyTopic)
    }

    @Test
    fun unveraendertesTopicHatDenPlanEmpfohlenenDefault() {
        // Gegenprobe zur Migration: OHNE einen vorherigen Klartextwert darf nichts migriert
        // werden, der leere Default gilt weiterhin.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsManager(context, testSecurePrefs(context, "sicher_5"))
        assertEquals("", settings.ntfyTopic)
        assertEquals("https://ntfy.sh", settings.ntfyServer)
    }
}
