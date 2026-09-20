package com.example.lrmprotokoll.report

import android.content.Context
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Echter CPython-/Matplotlib-Aufruf auf IO; pro Prozess serialisiert (Matplotlib-Zustand). */
class ChaquopyReportRunner(context: Context) {
    private val appContext = context.applicationContext

    sealed interface Ergebnis {
        data class Erfolg(val pdfPfad: String) : Ergebnis
        data class Fehler(val nachricht: String, val ursache: Throwable? = null) : Ergebnis
    }

    /** Kleine JSON-Metadaten und private CSV-Pfade, niemals die Rohwerte selbst. */
    suspend fun erzeugeBericht(parameterJson: String): Ergebnis = withContext(Dispatchers.IO) {
        pythonMutex.withLock {
            try {
                // Die erlaubten Wurzeln stammen ausschließlich vom Android-Context.
                val parameter = try {
                    JSONObject(parameterJson)
                } catch (error: Exception) {
                    return@withLock Ergebnis.Fehler("Die Berichtsparameter sind kein gültiges JSON.", error)
                }
                parameter.put("privateRoots", JSONArray(listOf(
                    appContext.filesDir.canonicalPath, appContext.cacheDir.canonicalPath,
                )))
                val requestedOutput = File(parameter.optString("outputPath")).canonicalFile
                require(requestedOutput.toPath().startsWith(File(appContext.filesDir, "reports").canonicalFile.toPath())) {
                    "Der PDF-Zielpfad liegt außerhalb des privaten Berichtsordners."
                }
                if (!Python.isStarted()) Python.start(AndroidPlatform(appContext))
                val module = Python.getInstance().getModule("report_bridge")
                val path = module.callAttr("generate_report", parameter.toString()).toString()
                val output = File(path).canonicalFile
                check(output.toPath().startsWith(File(appContext.filesDir, "reports").canonicalFile.toPath())) {
                    "Der Bericht liegt außerhalb des privaten Berichtsordners."
                }
                check(output.isFile && output.length() > 0) { "Die PDF-Datei wurde nicht erzeugt." }
                Ergebnis.Erfolg(output.absolutePath)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: PyException) {
                val message = error.message.orEmpty().lineSequence().firstOrNull().orEmpty()
                    .removePrefix("ValueError: ").removePrefix("RuntimeError: ")
                Ergebnis.Fehler("Bericht konnte nicht erzeugt werden: ${message.ifBlank { "Python-Verarbeitung fehlgeschlagen." }}", error)
            } catch (error: Exception) {
                Ergebnis.Fehler("Bericht konnte nicht erzeugt werden: ${error.message ?: "Dateifehler"}", error)
            }
        }
    }

    companion object {
        private val pythonMutex = Mutex()
    }
}
