package com.example.lrmprotokoll.diagnose

import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AusfallErfassungTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun prozessOhneAbschlussErzeugtNurEinenBeleg() {
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        val settings = app.container.settingsManager
        val datenAt = 2_000_000_000_000L
        val jetzt = datenAt + 500 * 60_000L
        val datei = LetzterZustandDatei(File(temp.root, "letzter_zustand.json"))
        datei.schreiben(
            LetzterZustand(
                zeitpunkt = datenAt,
                akkuProzent = 80,
                stromquelle = Stromzustand.Quelle.NETZTEIL.name,
                akkuStatus = Stromzustand.Status.LAEDT.name,
                uptimeMs = 50_000_000L,
                bootCount = 239,
                letzteDatenAt = datenAt,
                bleZustand = "VERBUNDEN",
                mikrofonAktiv = false,
                dienstLaeuft = true,
                sauberBeendet = false,
                dozeAktiv = false,
                energiesparModus = false,
                hintergrundEingeschraenkt = false,
                standbyBucket = 10,
                waechterLetzterLaufAt = null,
                heartbeatLetzterErfolgAt = null,
            ),
        )
        val ring = LebenszyklusRingFile(temp.root)
        settings.waechterLetzterLaufAt = datenAt + 60_000L
        settings.ausfallLetzteDatenAt = 0L
        try {
            val erfassung = AusfallErfassung(datei, ring, settings)
            val fall = erfassung.beimProzessstart(datenAt, 100_000_000L, 239, jetzt)
            assertEquals(AusfallKlasse.STILLSTAND_GERAET_LIEF, fall?.klasse)
            assertEquals(WaechterLief.JA, fall?.waechterLief)
            erfassung.beimProzessstart(datenAt, 100_000_000L, 239, jetzt + 1000L)
            ring.wartenBisFertig()
            val eintraege = ring.lesen().filter { it.ereignis == "Ausfall eingeordnet" }
            assertEquals(1, eintraege.size)
            assertEquals("STILLSTAND_GERAET_LIEF", eintraege.single().details["klassifikation"])
            assertTrue(eintraege.single().details.containsKey("bootCountVorher"))
        } finally {
            settings.waechterLetzterLaufAt = 0L
            settings.ausfallLetzteDatenAt = 0L
            settings.ausfallKlassifikation = null
            settings.ausfallWaechterLief = null
        }
    }
}
