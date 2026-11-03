package com.example.lrmprotokoll.diagnose

import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
class LetzterZustandDateiTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun atomarUeberschreibenUndBeschaedigteDateiErkennen() {
        val datei = File(temp.root, "letzter_zustand.json")
        val speicher = LetzterZustandDatei(datei)
        val erfasser = erfasser(speicher)

        assertNull(speicher.lesen())
        erfasser.aktualisieren(letzteDatenAt = 1234L, sauberBeendet = false)
        assertEquals(1234L, speicher.lesen()?.letzteDatenAt)
        assertFalse(speicher.lesen()!!.sauberBeendet)

        erfasser.aktualisieren(sauberBeendet = true)
        assertEquals(1234L, speicher.lesen()?.letzteDatenAt)
        assertTrue(speicher.lesen()!!.sauberBeendet)

        datei.writeText("{\"zeitpunkt\":")
        assertNull(speicher.lesen())
        erfasser.aktualisieren(letzteDatenAt = 5678L, sauberBeendet = false)
        assertEquals(5678L, speicher.lesen()?.letzteDatenAt)
    }

    private fun erfasser(datei: LetzterZustandDatei): LetzterZustandErfasser {
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        return LetzterZustandErfasser(
            datei = datei,
            system =
                SystemZustandQuelle {
                    SystemZustand(
                        zeitpunkt = 2000L,
                        akkuProzent = 80,
                        stromquelle = Stromzustand.Quelle.NETZTEIL.name,
                        akkuStatus = Stromzustand.Status.LAEDT.name,
                        uptimeMs = 1000L,
                        bootCount = 239,
                        dozeAktiv = false,
                        energiesparModus = false,
                        hintergrundEingeschraenkt = false,
                        standbyBucket = 10,
                    )
                },
            settings = app.container.settingsManager,
            bleZustand = { "VERBUNDEN" },
            mikrofonAktiv = { false },
            dienstLaeuft = { true },
        )
    }
}
