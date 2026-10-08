package com.example.lrmprotokoll.diagnose

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.data.SettingsManager
import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests fuer [LebenszyklusRingFile] und [LebenszyklusProzessUeberwachung]
 * (docs/PROMPT_FIX_DIAGNOSEFENSTER_LEBENSZYKLUS.md Abschnitt 3 Tests 1 und 2).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LebenszyklusRingFileTest {

    private lateinit var verzeichnis: File

    @Before
    fun aufbauen() {
        verzeichnis = File.createTempFile("lebenszyklus_ring", "dir").apply {
            delete()
            mkdirs()
        }
    }

    @After
    fun aufraeumen() {
        verzeichnis.deleteRecursively()
    }

    @Test
    fun schreibtUndLiestEintragMitRedaktion() {
        val ring = LebenszyklusRingFile(verzeichnis)
        ring.protokolliere(
            ereignis = "Test-Ereignis",
            details = mapOf(
                "token" to "geheim123",
                "email" to "test@example.com",
                "normal" to 42,
            ),
            timestampMillis = 1000L,
        )
        ring.wartenBisFertig()

        val eintraege = ring.lesen()
        assertEquals(1, eintraege.size)
        assertEquals("Test-Ereignis", eintraege[0].ereignis)
        assertEquals(1000L, eintraege[0].timestampMillis)
        assertEquals("[REDACTED]", eintraege[0].details["token"])
        assertEquals("[REDACTED_EMAIL]", eintraege[0].details["email"])
        assertEquals(42, eintraege[0].details["normal"])
    }

    /**
     * Test 1 aus docs/PROMPT_FIX_DIAGNOSEFENSTER_LEBENSZYKLUS.md Abschnitt 3:
     * Die Ringdatei rotiert an der Grenze (64 KB), und die aeltesten Eintraege fallen weg.
     */
    @Test
    fun ringdateiRotiertAnDerGrenzeUndAeltesteEintraegeFallenWeg() {
        val ring = LebenszyklusRingFile(verzeichnis)
        val fuelltext = "x".repeat(512)

        // Viele Eintraege schreiben, um mindestens 2x die Obergrenze (64 KB) zu ueberschreiten
        repeat(300) { i ->
            ring.protokolliere(
                ereignis = "Eintrag Nummer $i",
                details = mapOf("padding" to fuelltext),
                timestampMillis = i.toLong(),
            )
        }
        ring.wartenBisFertig(timeoutSekunden = 15)

        val dateiA = File(verzeichnis, "lebenszyklus_a.jsonl")
        val dateiB = File(verzeichnis, "lebenszyklus_b.jsonl")
        assertTrue("Datei A muss existieren", dateiA.exists())
        assertTrue("Datei B muss existieren und gefuellt sein (mindestens eine Rotation)", dateiB.exists() && dateiB.length() > 0)

        val gesamtgroesse = dateiA.length() + dateiB.length()
        assertTrue(
            "Gesamtgroesse ($gesamtgroesse) darf 2x Obergrenze nie ueberschreiten",
            gesamtgroesse <= 2 * LebenszyklusRingFile.DATEI_OBERGRENZE_BYTES,
        )
        assertTrue(
            "Datei A darf Obergrenze nicht ueberschreiten",
            dateiA.length() <= LebenszyklusRingFile.DATEI_OBERGRENZE_BYTES,
        )
        assertTrue(
            "Datei B darf Obergrenze nicht ueberschreiten",
            dateiB.length() <= LebenszyklusRingFile.DATEI_OBERGRENZE_BYTES,
        )

        val gelesen = ring.lesen()
        // Durch mindestens 2 Rotationen muessen die allerersten Eintraege (Index 0) verdraengt worden sein
        assertTrue(
            "Die aeltesten Eintraege muessen weggefallen sein",
            gelesen.none { it.ereignis == "Eintrag Nummer 0" },
        )
        // Aber die juengsten Eintraege muessen vorhanden sein
        assertTrue(
            "Die neuesten Eintraege muessen vorhanden sein",
            gelesen.any { it.ereignis == "Eintrag Nummer 299" },
        )
    }

    /**
     * Test 2 aus docs/PROMPT_FIX_DIAGNOSEFENSTER_LEBENSZYKLUS.md Abschnitt 3:
     * Zwei Prozessstarts mit Fake-Uhr und Fake-elapsedRealtime:
     * zweiter Start mit kleiner Uptime -> Eintrag "Gerät wurde neu gestartet";
     * grosse Uptime -> kein solcher Eintrag.
     */
    @Test
    fun zweiProzessstartsMitKleinerUndGrosserUptime() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val settings = SettingsManager(context)
        val ring = LebenszyklusRingFile(verzeichnis)

        // 1. Erster Start: Ausgangszustand etablieren
        settings.letzterProzessStartAt = 0L
        settings.letzterProzessStartElapsedRealtime = 0L

        val t1 = 1_000_000L
        val uptime1 = 500_000L
        LebenszyklusProzessUeberwachung.auswerten(
            settingsManager = settings,
            lebenszyklusRingFile = ring,
            pid = 1001,
            elapsedRealtimeMs = uptime1,
            versionCode = 42L,
            jetztMs = t1,
        )
        ring.wartenBisFertig()

        var eintraege = ring.lesen()
        assertEquals(1, eintraege.size)
        assertEquals("Prozessstart", eintraege[0].ereignis)
        assertEquals(1001, eintraege[0].details["pid"])
        assertEquals(uptime1, (eintraege[0].details["elapsedRealtimeMs"] as Number).toLong())
        assertEquals(t1, settings.letzterProzessStartAt)
        assertEquals(uptime1, settings.letzterProzessStartElapsedRealtime)

        // 2. Zweiter Start mit grosser Uptime (kein Neustart des Geraets, System lief kontinuierlich)
        val t2 = t1 + 600_000L // 10 Minuten spaeter
        val uptime2 = uptime1 + 600_000L // Uptime um 10 Minuten gewachsen
        LebenszyklusProzessUeberwachung.auswerten(
            settingsManager = settings,
            lebenszyklusRingFile = ring,
            pid = 1002,
            elapsedRealtimeMs = uptime2,
            versionCode = 42L,
            jetztMs = t2,
        )
        ring.wartenBisFertig()

        eintraege = ring.lesen()
        assertEquals(2, eintraege.size)
        // Beide Eintraege sind reine Prozessstarts, kein Geraeteneustart-Eintrag
        assertFalse(
            "Bei grosser Uptime darf kein Neustart-Eintrag vorliegen",
            eintraege.any { it.ereignis == "Gerät wurde neu gestartet" },
        )

        // 3. Dritter Start mit kleiner Uptime (Geraet wurde zwischenzeitlich neu gestartet!)
        val t3 = t2 + 300_000L // 5 Minuten spaeter
        val uptime3 = 20_000L // Nur 20 Sekunden seit Boot, aber 5 Minuten seit letztem App-Start!
        LebenszyklusProzessUeberwachung.auswerten(
            settingsManager = settings,
            lebenszyklusRingFile = ring,
            pid = 2001,
            elapsedRealtimeMs = uptime3,
            versionCode = 42L,
            jetztMs = t3,
        )
        ring.wartenBisFertig()

        eintraege = ring.lesen()
        val neustartEintrag = eintraege.find { it.ereignis == "Gerät wurde neu gestartet" }
        assertTrue("Bei kleiner Uptime muss ein Geraeteneustart erkannt werden", neustartEintrag != null)
        assertEquals(uptime3, (neustartEintrag!!.details["elapsedRealtimeMs"] as Number).toLong())
        assertEquals(300_000L, (neustartEintrag.details["abstandLetzterStartMs"] as Number).toLong())
    }

    @Test
    fun beimStartBeschneidenKuerztUeberdimensionierteDatei() {
        val dateiA = File(verzeichnis, "lebenszyklus_a.jsonl")
        // Schreiben einer Datei, die die Obergrenze um 5 KB ueberschreitet
        val ueberschussZeile = "{\"timestamp\": 1, \"ereignis\": \"Alt\", \"details\": {}}\n"
        val payload = ueberschussZeile.repeat(1500).toByteArray(StandardCharsets.UTF_8)
        dateiA.writeBytes(payload)
        assertTrue(dateiA.length() > LebenszyklusRingFile.DATEI_OBERGRENZE_BYTES)

        val ring = LebenszyklusRingFile(verzeichnis)
        ring.beimStartBeschneiden()
        ring.wartenBisFertig()

        assertTrue(
            "Datei A muss nach dem Beschneiden <= Obergrenze sein",
            dateiA.length() <= LebenszyklusRingFile.DATEI_OBERGRENZE_BYTES,
        )
    }

    /**
     * Review-Befund zu PR #266: Ein Vorstellen der Wanduhr (Netzzeit) ohne Neustart liess den
     * Wanduhr-Abstand groesser werden als die Uptime und meldete einen Neustart, den es nie gab.
     */
    @Test
    fun vorgestellteUhrOhneNeustartMeldetKeinenNeustart() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val settings = SettingsManager(context)
        val ring = LebenszyklusRingFile(verzeichnis)
        val stunde = 3_600_000L

        LebenszyklusProzessUeberwachung.auswerten(
            settingsManager = settings,
            lebenszyklusRingFile = ring,
            elapsedRealtimeMs = 60_000L, // 1 min nach dem Boot, Uhr geht noch 1 h nach
            jetztMs = 10 * stunde,
            bootCount = 7,
        )
        // 2 h spaeter laut Uptime, aber die Uhr wurde inzwischen um 1 h vorgestellt: 3 h Abstand.
        LebenszyklusProzessUeberwachung.auswerten(
            settingsManager = settings,
            lebenszyklusRingFile = ring,
            elapsedRealtimeMs = 60_000L + 2 * stunde,
            jetztMs = 13 * stunde,
            bootCount = 7,
        )
        // Ohne BOOT_COUNT (Fallback) ebenso kein Fehlalarm.
        LebenszyklusProzessUeberwachung.auswerten(
            settingsManager = settings,
            lebenszyklusRingFile = ring,
            elapsedRealtimeMs = 60_000L + 2 * stunde + 1_000L,
            jetztMs = 20 * stunde,
            bootCount = null,
        )
        ring.wartenBisFertig()

        assertFalse(ring.lesen().any { it.ereignis == "Gerät wurde neu gestartet" })
    }

    @Test
    fun geaenderterBootCountMeldetNeustartAuchBeiGroessererUptime() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val settings = SettingsManager(context)
        val ring = LebenszyklusRingFile(verzeichnis)

        LebenszyklusProzessUeberwachung.auswerten(
            settingsManager = settings,
            lebenszyklusRingFile = ring,
            elapsedRealtimeMs = 60_000L,
            jetztMs = 1_000_000L,
            bootCount = 7,
        )
        // Neustart, und die App startet erst 10 min nach dem Boot: Uptime groesser als zuvor.
        LebenszyklusProzessUeberwachung.auswerten(
            settingsManager = settings,
            lebenszyklusRingFile = ring,
            elapsedRealtimeMs = 600_000L,
            jetztMs = 5_000_000L,
            bootCount = 8,
        )
        ring.wartenBisFertig()

        val neustart = ring.lesen().filter { it.ereignis == "Gerät wurde neu gestartet" }
        assertEquals(1, neustart.size)
        assertEquals(8, (neustart[0].details["bootCount"] as Number).toInt())
        assertEquals(8, settings.letzterProzessStartBootCount)
    }

    /**
     * Review-Befund zu PR #266: Fiel die Rotation in dieselbe mtime-Sekunde wie der letzte Eintrag
     * der vollen Datei, waehlte ein neuer Prozess die volle Datei und leerte beim naechsten
     * Eintrag die andere - mit den neuesten Eintraegen darin.
     */
    @Test
    fun gleicheAenderungszeitWaehltDieFrischRotierteDatei() {
        val dateiA = File(verzeichnis, "lebenszyklus_a.jsonl")
        val dateiB = File(verzeichnis, "lebenszyklus_b.jsonl")
        val alteZeile = "{\"timestamp\": 1, \"ereignis\": \"Alt\", \"details\": {}}\n"
        val voll = alteZeile.repeat((LebenszyklusRingFile.DATEI_OBERGRENZE_BYTES / alteZeile.length).toInt())
        dateiB.writeText(voll, StandardCharsets.UTF_8)
        dateiA.writeText("{\"timestamp\": 2, \"ereignis\": \"Neu\", \"details\": {}}\n", StandardCharsets.UTF_8)
        val gleicheZeit = 1_700_000_000_000L
        dateiA.setLastModified(gleicheZeit)
        dateiB.setLastModified(gleicheZeit)

        val ring = LebenszyklusRingFile(verzeichnis)
        ring.protokolliere(ereignis = "Danach", timestampMillis = 3L)
        ring.wartenBisFertig()

        val ereignisse = ring.lesen().map { it.ereignis }
        assertTrue("Der neueste Eintrag vor dem Neustart darf nicht verloren gehen", "Neu" in ereignisse)
        assertTrue("Danach" in ereignisse)
    }
}
