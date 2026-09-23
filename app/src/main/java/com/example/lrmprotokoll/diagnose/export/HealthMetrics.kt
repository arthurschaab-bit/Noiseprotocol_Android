package com.example.lrmprotokoll.diagnose.export

import com.example.lrmprotokoll.data.ConnectionEventEntity
import com.example.lrmprotokoll.data.ConnectionEventType
import com.example.lrmprotokoll.diagnose.DiagnosticCode
import com.example.lrmprotokoll.diagnose.DiagnosticEvent
import org.json.JSONObject

/**
 * Kennzahlen fuer das periodische Gesundheits-Bundle (M12 Schritt 6, Konzept Abschnitt 6 Schritt
 * 6 Aufgabe 3) - "nur im Zeitverlauf aussagekraeftig": ein einzelner Wert sagt nichts, erst der
 * Vergleich ueber mehrere Tage (mehrere periodische Bundles in Drive) zeigt einen Trend. Reine
 * Funktion ([berechneHealthMetrics]), deshalb ohne Android-Laufzeit mit Fake-Daten pruefbar.
 *
 * [heapHochstandBytes] ist bewusst eine Momentaufnahme der Heap-Nutzung beim Bauen dieses
 * Bundles, kein kontinuierlich ueber den ganzen Tag mitgefuehrter Spitzenwert - letzteres wuerde
 * eine Stichprobenahme an einer Hot-Path-Stelle (z.B. jeder Breadcrumb) erfordern, was Konzept
 * 4.3 fuer den absturzkritischen Pfad ausdruecklich vermeiden will ("Sparsam mit Allokationen").
 * Als taeglicher Datenpunkt in einer Zeitreihe ueber viele Bundles hinweg erfuellt die
 * Momentaufnahme denselben Zweck (einen Trend sichtbar machen), ohne diesen Pfad zu beruehren.
 */
data class HealthMetrics(
    val reconnectCount: Int,
    val decodeFehlerCount: Int,
    val diagnosticLogEntryCount: Long,
    val dbWachstumBytes: Long,
    val heapHochstandBytes: Long,
    val fehlerJeCode: Map<DiagnosticCode, Int>,
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("reconnectCount", reconnectCount)
        json.put("decodeFehlerCount", decodeFehlerCount)
        json.put("diagnosticLogEntryCount", diagnosticLogEntryCount)
        json.put("dbWachstumBytes", dbWachstumBytes)
        json.put("heapHochstandBytes", heapHochstandBytes)
        val fehlerObj = JSONObject()
        fehlerJeCode.forEach { (code, anzahl) -> fehlerObj.put(code.name, anzahl) }
        json.put("fehlerJeCode", fehlerObj)
        return json
    }

    /**
     * "Kein Bundle ohne Not" (Konzept Aufgabe 5): hat sich seit dem letzten periodischen Bundle
     * nichts Relevantes geaendert und gab es keinen Fehler, entsteht keines - sonst fuellt sich
     * Drive mit Nullmeldungen. [heapHochstandBytes] fliesst bewusst NICHT ein: der Heapstand
     * schwankt staendig geringfuegig, auch ohne jedes Problem, und wuerde die "ohne Not"-Pruefung
     * sonst nie zuschlagen lassen.
     */
    fun istUnveraendert(): Boolean =
        reconnectCount == 0 &&
            decodeFehlerCount == 0 &&
            diagnosticLogEntryCount == 0L &&
            dbWachstumBytes <= 0L &&
            fehlerJeCode.values.all { it == 0 }
}

/**
 * Baut die Kennzahlen aus bereits geladenen Daten (Aufgabe 3) - der Aufrufer ([SupportBundleHealthCoordinator])
 * ist fuer das Laden aus Room/[com.example.lrmprotokoll.diagnose.DiagnosticsReporter] zustaendig,
 * damit diese Funktion selbst ohne Android-Laufzeit testbar bleibt.
 */
fun berechneHealthMetrics(
    connectionEventsSeitLetztemBundle: List<ConnectionEventEntity>,
    diagnosticLogEntryCountSeitLetztemBundle: Long,
    dbGroesseAktuellBytes: Long,
    dbGroesseLetztesBundleBytes: Long,
    heapHochstandBytes: Long,
    eventsSeitLetztemBundle: List<DiagnosticEvent>,
): HealthMetrics {
    // Reconnect-Zyklen wie im Diagnose-Screen (Reconnects.kt): DEGRADED/DISCONNECTED markiert
    // den Beginn einer Ausfallperiode, die einen Reconnect-Versuch ausgeloest hat - hier aber
    // app-weit statt je Session.
    val reconnectCount = connectionEventsSeitLetztemBundle.count {
        it.type == ConnectionEventType.DEGRADED || it.type == ConnectionEventType.DISCONNECTED
    }
    val decodeFehlerCount = eventsSeitLetztemBundle.count { it.code == DiagnosticCode.BLE_DECODE_RATE_HIGH }
    val fehlerJeCode = eventsSeitLetztemBundle.groupingBy { it.code }.eachCount()
    return HealthMetrics(
        reconnectCount = reconnectCount,
        decodeFehlerCount = decodeFehlerCount,
        diagnosticLogEntryCount = diagnosticLogEntryCountSeitLetztemBundle,
        dbWachstumBytes = (dbGroesseAktuellBytes - dbGroesseLetztesBundleBytes).coerceAtLeast(0L),
        heapHochstandBytes = heapHochstandBytes,
        fehlerJeCode = fehlerJeCode,
    )
}
