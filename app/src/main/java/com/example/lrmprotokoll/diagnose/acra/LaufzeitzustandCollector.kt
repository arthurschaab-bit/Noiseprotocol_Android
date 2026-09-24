package com.example.lrmprotokoll.diagnose.acra

import android.content.Context
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.audio.AudioRecordingService
import com.example.lrmprotokoll.diagnose.LaufzeitzustandJson
import com.google.auto.service.AutoService
import org.acra.builder.ReportBuilder
import org.acra.collector.Collector
import org.acra.config.CoreConfiguration
import org.acra.data.CrashReportData
import org.json.JSONObject

/** Schluessel, unter dem der Laufzeitzustand DES ABSTURZMOMENTS im Report landet. */
const val LAUFZEITZUSTAND_REPORT_KEY = "LAUFZEITZUSTAND"

/**
 * Rueckgabewert, wenn der BLE-Zustand ohne Initialisierung schwerer Teile nicht ermittelbar ist -
 * dieselbe Bedeutung wie der bisherige Default von
 * [com.example.lrmprotokoll.diagnose.export.SupportBundleExporter]s
 * `bleVerbindungszustandProvider`.
 */
internal const val BLE_ZUSTAND_UNBEKANNT = "UNBEKANNT"

/**
 * Holt eine Planabweichung aus M12 nach (docs/DIAGNOSE_CRASH_KONZEPT.md, Diagramm
 * "Absturzmoment": dort als `LaufzeitzustandCollector` vorgesehen, nie gebaut - siehe
 * docs/BEFUNDE_P30_2026-09-23.md Abschnitt 3a / docs/PROMPT_FIX_LAUFZEITZUSTAND_ABSTURZ.md).
 *
 * Erfasst Heap, Speicherinfo, Aufnahme- und BLE-Zustand IM ABSTUERZENDEN PROZESS, zum
 * Absturzzeitpunkt selbst - anders als `state/runtime.json`
 * ([com.example.lrmprotokoll.diagnose.export.SupportBundleExporter.buildRuntimeJson]), das der
 * ACRA-Sender-Prozess (`:acra`, ein ANDERER Prozess - siehe [SupportOutboxReportSender]-KDoc)
 * erst NACH dem Absturz baut und das deshalb strukturell nie den Absturzmoment zeigen kann:
 * statische/Companion-Zustaende wie [AudioRecordingService.audioAufnahmeAktiv] sind pro
 * OS-Prozess unabhaengig initialisiert, im frischen Sender-Prozess also immer auf ihrem
 * Startwert.
 *
 * Wie [BreadcrumbRingCollector]: eigener Report-Schluessel statt ACRAs eingebautem
 * `CUSTOM_DATA` (dessen Ausfuehrungsreihenfolge relativ zu diesem Collector nicht garantiert
 * ist).
 *
 * **Besonders wichtig bei [OutOfMemoryError]** (der haeufigste Absturzgrund auf dem
 * Owner-Geraet, siehe BEFUNDE_P30 Abschnitt 2): dieser Collector liest AUSSCHLIESSLICH bereits
 * vorhandene Zustaende und initialisiert nichts:
 * - Heap/`ActivityManager.MemoryInfo`/laufende Dienste ueber [LaufzeitzustandJson] (geteilt mit
 *   dem Exporter - nur die billigen Teile).
 * - Aufnahmezustand direkt vom statischen [AudioRecordingService.audioAufnahmeAktiv]
 *   (Companion-Object-StateFlow, existiert unabhaengig von jeder Objektinstanz, kein Zugriff auf
 *   [com.example.lrmprotokoll.AppContainer] noetig).
 * - BLE-Zustand nur, wenn [LaermprotokollApp.container] UND darin dessen `ConnectionSupervisor`
 *   bereits gebaut sind (siehe `AppContainer.connectionSupervisorFallsBereitsInitialisiert()`) -
 *   sonst faellt das Feld auf [BLE_ZUSTAND_UNBEKANNT] zurueck, OHNE die schwere BLE-Kette
 *   (BleMeterTransport, BluetoothAdapterStateObserver, ConnectionSupervisor selbst)
 *   anzustossen. In der Praxis ist der Supervisor bei einem Absturz waehrend laufender
 *   Aufzeichnung immer schon gebaut - der [AudioRecordingService] liest ihn beim Start.
 * - Keine Berechtigungsliste, keine Einstellungen (Auftrag: "zu teuer, im Absturzmoment nicht
 *   noetig") - die bleiben in `state/runtime.json`.
 * - Jede Einzelangabe in `runCatching` (faengt implizit auch [Throwable], nicht nur
 *   [Exception] - ein zweiter [OutOfMemoryError] ist ein [Error], keine Exception): ein
 *   Fehlschlag darf den Report nicht verhindern.
 *
 * Per ServiceLoader registriert (`@AutoService`) - braucht deshalb einen ECHTEN oeffentlichen
 * No-Arg-Konstruktor (siehe der sekundaere Konstruktor unten). Der Primaerkonstruktor mit den
 * injizierbaren Quellen ist nur fuer Tests gedacht (`internal`).
 */
@AutoService(Collector::class)
class LaufzeitzustandCollector internal constructor(
    private val aufnahmeAktivQuelle: () -> Boolean,
    private val bleVerbindungszustandQuelle: (Context) -> String,
    private val zeitstempelQuelle: () -> Long,
) : Collector {
    constructor() : this(
        aufnahmeAktivQuelle = { AudioRecordingService.audioAufnahmeAktiv.value },
        bleVerbindungszustandQuelle = ::bleZustandOhneInitialisierung,
        zeitstempelQuelle = { System.currentTimeMillis() },
    )

    override fun collect(
        context: Context,
        config: CoreConfiguration,
        reportBuilder: ReportBuilder,
        crashReportData: CrashReportData,
    ) {
        val json = JSONObject()

        // Billige, mit SupportBundleExporter.buildRuntimeJson() geteilte Felder - kapselt seine
        // eigene Fehlerisolierung je Gruppe (Heap / MemoryInfo / laufende Dienste).
        runCatching { LaufzeitzustandJson.schreibeGemeinsameFelder(context, json) }

        runCatching { json.put("aufnahmeAktiv", aufnahmeAktivQuelle()) }
        runCatching { json.put("bleVerbindungszustand", bleVerbindungszustandQuelle(context)) }
        runCatching { json.put("erfasstUm", zeitstempelQuelle()) }

        runCatching { crashReportData.put(LAUFZEITZUSTAND_REPORT_KEY, json.toString()) }
    }
}

/**
 * Siehe Klassen-KDoc oben. Baut weder [LaermprotokollApp.container] noch dessen
 * `ConnectionSupervisor` auf - liest nur, was zum Aufrufzeitpunkt bereits existiert.
 */
private fun bleZustandOhneInitialisierung(context: Context): String {
    val app = context.applicationContext as? LaermprotokollApp ?: return BLE_ZUSTAND_UNBEKANNT
    if (!app.isContainerInitialized()) return BLE_ZUSTAND_UNBEKANNT
    val supervisor = app.container.connectionSupervisorFallsBereitsInitialisiert() ?: return BLE_ZUSTAND_UNBEKANNT
    return supervisor.state.value.toString()
}
