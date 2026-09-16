package com.example.lrmprotokoll.report

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Geraetetest-Checkliste F13, Zeile 1: bis `report_bridge.py` existiert (Schritt 4), muss ein
 * echter Chaquopy-Aufruf einen verstaendlichen Fehler statt eines Absturzes liefern. Genauer:
 * [BerichtErstellenSheet]s `erzeugen()` erkennt den Modulfehler nur, wenn die Fehlermeldung
 * sowohl "report_bridge" als auch "module" enthaelt (`nachricht.contains("report_bridge") &&
 * nachricht.contains("module", ignoreCase = true)`) - bisher war das nur gegen eine im
 * Robolectric-Test selbst frei gewaehlte Fake-Meldung ("No module named 'report_bridge'")
 * geprueft, nie gegen die tatsaechliche `PyException`-Meldung des echten, im APK eingebetteten
 * CPython-Interpreters. Chaquopy laeuft nicht unter Robolectric (natives Modul fuer die echte
 * Geraete-ABI) - das ist deshalb nur auf einem echten Emulator/Geraet ueberhaupt pruefbar.
 */
@RunWith(AndroidJUnit4::class)
class ChaquopyReportRunnerInstrumentedTest {

    @Test
    fun fehlendesReportBridgeModulLiefertVomUiErkennbareFehlermeldung() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val runner = ChaquopyReportRunner(context)

        val ergebnis = runner.erzeugeBericht("{}")

        assertTrue(
            "Ohne report_bridge.py muss ein Fehler zurueckkommen, kein Erfolg: $ergebnis",
            ergebnis is ChaquopyReportRunner.Ergebnis.Fehler,
        )
        val nachricht = (ergebnis as ChaquopyReportRunner.Ergebnis.Fehler).nachricht
        assertTrue(
            "Die echte PyException-Meldung muss 'report_bridge' enthalten, sonst zeigt das UI " +
                "einen rohen Python-Fehler statt der verstaendlichen Meldung: $nachricht",
            nachricht.contains("report_bridge"),
        )
        assertTrue(
            "Die echte PyException-Meldung muss 'module' enthalten (Gross-/Kleinschreibung " +
                "egal), sonst zeigt das UI einen rohen Python-Fehler: $nachricht",
            nachricht.contains("module", ignoreCase = true),
        )
    }
}
