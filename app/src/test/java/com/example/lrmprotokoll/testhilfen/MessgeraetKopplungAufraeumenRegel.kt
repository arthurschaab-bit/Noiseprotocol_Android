package com.example.lrmprotokoll.testhilfen

import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Leert nach jedem Test die gepinnte Messgeraet-Kopplung aus den Einstellungen.
 *
 * **Warum das noetig ist.** Gradle fuehrt die JVM-Tests in einem Prozess aus, und
 * [com.example.lrmprotokoll.SettingsManager] schreibt in EncryptedSharedPreferences. Ein
 * `app.resetContainer()` tauscht den Container, nicht den Einstellungsspeicher - eine gepinnte
 * Adresse ueberlebt also die Testklasse und ist in der naechsten noch da. Am 01.10.2026 taten das
 * zehn Klassen; `GeraetetestS3CockpitTest` raeumte Dienstzustand, Supervisor und alle
 * Datenbanktabellen auf, nur die Kopplung nicht.
 *
 * **Warum das mehr ist als Ordnungsliebe.** `MainActivity.onResume` ruft
 * `MeterAutoConnect.verbindeWennGewuenscht()`, und das braucht genau zwei Dinge: den
 * Automatikschalter und eine gepinnte Adresse. Eine geleckte Adresse laesst eine spaetere
 * Testklasse also einen Verbindungsaufbau starten, den ihr Autor nicht vorgesehen hat - mit
 * Zustaenden wie SCANNING und CONNECTING, in denen [com.example.lrmprotokoll.ui.BluetoothStatusBadge]
 * animiert.
 *
 * **Was damit NICHT belegt ist.** Dass dies die Ursache des Leerlauf-Haengers aus
 * `docs/CI_FLAKINESS_UNTERSUCHUNG_BERICHT.md` Abschnitt 4.8 ist. Die Klasse allein haengt in 6 von
 * 6 Laeufen nicht, die sechs betroffenen Klassen zusammen in 5 von 5 auch nicht - nur der Vollauf
 * haengt. Diese Regel beseitigt eine belegte Verunreinigung; ob sie den Haenger beseitigt, zeigt
 * erst die Zaehlung danach.
 *
 * Der Bericht verlangt in Abschnitt 5 Punkt 3 genau diese Isolation fuer die Datenbank. Fuer die
 * Einstellungen hat es nie jemand aufgeschrieben.
 */
class MessgeraetKopplungAufraeumenRegel : TestRule {
    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        object : Statement() {
            override fun evaluate() {
                try {
                    base.evaluate()
                } finally {
                    // Nach dem @After der Klasse, damit ein dortiges resetContainer() nichts
                    // zurueckschreibt. Fehler hier duerfen einen gruenen Test nicht rot machen
                    // und einen roten nicht verdecken.
                    runCatching {
                        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
                        app.container.settingsManager.meterDeviceAddress = null
                        app.container.settingsManager.meterDeviceName = null
                    }
                }
            }
        }
}
