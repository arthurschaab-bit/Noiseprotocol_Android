package com.example.lrmprotokoll.diagnose.acra

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.AppContainer
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.audio.AudioRecordingService
import com.example.lrmprotokoll.meter.FakeMeterTransport
import org.acra.builder.ReportBuilder
import org.acra.collector.Collector
import org.acra.data.CrashReportData
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.ServiceLoader

/**
 * Bugfix docs/PROMPT_FIX_LAUFZEITZUSTAND_ABSTURZ.md, Abschnitt 3, Tests 1 und 2 - plus einige
 * zusaetzliche Faelle rund um die BLE-Verdrahtung aus Schritt 2. Vorbild ist
 * [BreadcrumbRingCollectorTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaufzeitzustandCollectorTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val app: LaermprotokollApp get() = ApplicationProvider.getApplicationContext()

    @After
    fun aufraeumen() {
        // Test 1 (Auftrag Abschnitt 3): "Den Testzustand danach zuruecksetzen, damit
        // nachfolgende Tests nicht verfaelscht werden." Als @After statt eines manuellen
        // try/finally, damit die Ruecksetzung auch bei einer fehlschlagenden Assertion greift.
        AudioRecordingService.testSetzeAudioAufnahmeAktiv(false)
    }

    /** Test 1 aus dem Auftrag. */
    @Test
    fun erfasstAufnahmeAktivUndPlausibleHeapwerte() {
        AudioRecordingService.testSetzeAudioAufnahmeAktiv(true)

        val crashReportData = CrashReportData()
        LaufzeitzustandCollector().collect(context, AcraConfig.build(), ReportBuilder(), crashReportData)

        val json = JSONObject(crashReportData.get(LAUFZEITZUSTAND_REPORT_KEY) as String)
        assertTrue("aufnahmeAktiv muss true sein", json.getBoolean("aufnahmeAktiv"))
        assertTrue("heapMaxBytes muss plausibel sein (> 0)", json.getLong("heapMaxBytes") > 0)
    }

    /** Test 2 aus dem Auftrag: eine einzelne injizierte Fake-Quelle wirft. */
    @Test
    fun robustGegenEineFehlschlagendeQuelle() {
        val collector =
            LaufzeitzustandCollector(
                aufnahmeAktivQuelle = { true },
                bleVerbindungszustandQuelle = { throw RuntimeException("simulierter Fehler der BLE-Quelle") },
                zeitstempelQuelle = { 1_700_000_000_000L },
            )
        val crashReportData = CrashReportData()

        collector.collect(context, AcraConfig.build(), ReportBuilder(), crashReportData)

        val json = JSONObject(crashReportData.get(LAUFZEITZUSTAND_REPORT_KEY) as String)
        assertFalse(
            "bleVerbindungszustand muss fehlen, wenn nur seine Quelle wirft",
            json.has("bleVerbindungszustand"),
        )
        assertTrue("aufnahmeAktiv muss trotzdem da sein", json.getBoolean("aufnahmeAktiv"))
        assertEquals("erfasstUm muss trotzdem da sein", 1_700_000_000_000L, json.getLong("erfasstUm"))
        assertTrue("heapMaxBytes muss trotzdem da sein (Heap-Gruppe ist unabhaengig)", json.getLong("heapMaxBytes") > 0)
    }

    /**
     * Schritt 2 des Auftrags ("ohne Initialisierung schwerer Teile"): ist der
     * ConnectionSupervisor noch nicht gebaut, liefert der Default-Collector UNBEKANNT statt ihn
     * anzustossen.
     */
    @Test
    fun bleZustandBleibtUnbekanntWennConnectionSupervisorNochNichtGebaut() {
        app.setCustomContainer(AppContainer(app, FakeMeterTransport()))
        try {
            val crashReportData = CrashReportData()
            LaufzeitzustandCollector().collect(context, AcraConfig.build(), ReportBuilder(), crashReportData)

            val json = JSONObject(crashReportData.get(LAUFZEITZUSTAND_REPORT_KEY) as String)
            assertEquals(BLE_ZUSTAND_UNBEKANNT, json.getString("bleVerbindungszustand"))
        } finally {
            app.resetContainer()
        }
    }

    /**
     * Gegenprobe zum Test oben: ist der ConnectionSupervisor bereits gebaut (wie im
     * Aufnahmebetrieb - AudioRecordingService.onCreate() liest ihn beim Start), liefert der
     * Default-Collector den echten Zustand, ohne selbst etwas anzustossen.
     */
    @Test
    fun erfasstDenEchtenBleZustandWennConnectionSupervisorBereitsGebaut() {
        app.setCustomContainer(AppContainer(app, FakeMeterTransport()))
        try {
            // Simuliert, dass die App den ConnectionSupervisor laengst benutzt - der Collector
            // selbst darf das nicht ausloesen, hier tut es der Test bewusst.
            val echterZustand =
                app.container.connectionSupervisor.state.value
                    .toString()

            val crashReportData = CrashReportData()
            LaufzeitzustandCollector().collect(context, AcraConfig.build(), ReportBuilder(), crashReportData)

            val json = JSONObject(crashReportData.get(LAUFZEITZUSTAND_REPORT_KEY) as String)
            assertEquals(echterZustand, json.getString("bleVerbindungszustand"))
        } finally {
            app.resetContainer()
        }
    }

    @Test
    fun istUeberServiceLoaderAuffindbar() {
        // Bewacht einen leicht zu uebersehenden Stolperstein: ACRA laedt @AutoService-Collectors
        // ueber java.util.ServiceLoader, das zwingend einen OEFFENTLICHEN No-Arg-Konstruktor
        // braucht - Kotlin generiert den bei einem primaeren Konstruktor mit Default-Werten NICHT
        // automatisch als eigene Java-Ueberladung. Ohne den sekundaeren No-Arg-Konstruktor in
        // LaufzeitzustandCollector wuerden alle anderen Tests trotzdem bestehen (sie rufen den
        // Konstruktor direkt aus Kotlin auf), ServiceLoader wuerde den Collector in Produktion
        // aber stillschweigend uebergehen. Nur dieser Test deckt genau das auf.
        val alleCollectors = ServiceLoader.load(Collector::class.java, LaufzeitzustandCollector::class.java.classLoader)
        val gefunden = alleCollectors.toList().filterIsInstance<LaufzeitzustandCollector>()
        assertTrue(
            "LaufzeitzustandCollector muss ueber ServiceLoader (META-INF/services, @AutoService) auffindbar sein",
            gefunden.isNotEmpty(),
        )
    }
}
