package com.example.lrmprotokoll.report

import android.content.Context
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Kotlin-Bruecke zum embedded CPython-Interpreter (Chaquopy) fuer den geplanten High-End-Bericht
 * (Owner-Vorgabe 12.09.2026, Schritt 1: nur diese Wrapper-Klasse, das eigentliche Python-Modul
 * `report_bridge.py` mit `generate_report()` ist Schritt 4 und noch nicht Teil dieses Auftrags -
 * ein Aufruf von [erzeugeBericht] schlaegt bis dahin mit einem [PyException] fehl, weil das Modul
 * nicht existiert).
 *
 * Bewusst KEIN `PyApplication` in `AndroidManifest.xml`: die App hat mit [com.example.lrmprotokoll.LaermprotokollApp]
 * bereits eine eigene `Application`-Klasse (siehe [com.example.lrmprotokoll.AppContainer]), die zu
 * ersetzen ausserhalb dieses Auftrags läge. Stattdessen die von Chaquopy dokumentierte Alternative:
 * `Python.start()` einmalig und idempotent hier im Konstruktor, gegen `Python.isStarted()` geprüft.
 *
 * Laeuft bewusst NICHT auf dem Main-Thread ([erzeugeBericht] wechselt selbst auf [Dispatchers.IO]):
 * Matplotlib-Rendering und ReportLab-PDF-Aufbau sind CPU- und speicherintensiv (Owner-Vorgabe,
 * Schritt 7) - der eigentliche Foreground-Service/Worker mit Fortschrittsanzeige ist Schritt 3.
 */
class ChaquopyReportRunner(context: Context) {

    private val appContext = context.applicationContext

    init {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(appContext))
        }
    }

    /** Ergebnis eines Berichtslaufs - ohne Exceptions ueber die Kotlin/Python-Grenze zu reichen,
     * damit der Aufrufer (Schritt 3: Worker mit Fortschrittsanzeige) nicht selbst zwischen
     * [PyException] und regulaeren Kotlin-Exceptions unterscheiden muss. */
    sealed interface Ergebnis {
        /** [pdfPfad] ist der absolute Pfad der von `report_bridge.py` erzeugten PDF-Datei im
         * App-Speicher. */
        data class Erfolg(val pdfPfad: String) : Ergebnis

        /** [nachricht] ist bereits fuer die Anzeige aufbereitet (kein rohes Python-Traceback);
         * [ursache] bleibt fuer Diagnose-Log/Sentry erhalten. */
        data class Fehler(val nachricht: String, val ursache: Throwable? = null) : Ergebnis
    }

    /**
     * Ruft `report_bridge.generate_report(parameterJson)` auf (Schritt 4). [parameterJson] traegt
     * alles, was das Python-Modul zur Auswertung braucht: DB-Pfad bzw. exportierte CSV-Pfade,
     * die § 287 ZPO-Schaetzparameter aus [com.example.lrmprotokoll.data.ReportConfigEntity],
     * Zeitraum, Standortwechsel und die Rohdaten-Pruefsummen fuer das Manifest - strukturiert
     * uebergeben statt als CLI-Argumente (Owner-Vorgabe Schritt 7: "JSON-Parameter + CSV-Pfade").
     *
     * `report_bridge.generate_report()` muss synchron einen String mit dem absoluten PDF-Pfad
     * zurueckgeben - deshalb hier [Dispatchers.IO] statt den Aufrufer zwingen, das selbst zu tun.
     */
    suspend fun erzeugeBericht(parameterJson: String): Ergebnis = withContext(Dispatchers.IO) {
        try {
            val modul = Python.getInstance().getModule("report_bridge")
            val pdfPfad = modul.callAttr("generate_report", parameterJson).toString()
            Ergebnis.Erfolg(pdfPfad)
        } catch (e: PyException) {
            Ergebnis.Fehler("Python-Fehler bei der Berichtserzeugung: ${e.message}", e)
        } catch (e: Exception) {
            Ergebnis.Fehler("Unerwarteter Fehler bei der Berichtserzeugung: ${e.message}", e)
        }
    }
}
