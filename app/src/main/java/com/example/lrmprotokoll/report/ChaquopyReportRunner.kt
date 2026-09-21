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
                // Erlaubte Verzeichnisse passieren getrennt vom untrusted Parameter-JSON die
                // Python-Grenze. Ein eingeschleustes `privateRoots` kann die Allowlist damit
                // nicht erweitern.
                parameter.remove("privateRoots")
                val privateDirectories = JSONObject()
                    .put("filesDir", appContext.filesDir.canonicalPath)
                    .put("cacheDir", appContext.cacheDir.canonicalPath)
                val reportsDirectory = File(appContext.filesDir, "reports").canonicalFile
                val requestedOutput = File(parameter.optString("outputPath")).canonicalFile
                require(requestedOutput.toPath().startsWith(reportsDirectory.toPath())) {
                    "Der PDF-Zielpfad liegt außerhalb des privaten Berichtsordners."
                }
                if (!Python.isStarted()) Python.start(AndroidPlatform(appContext))
                val module = Python.getInstance().getModule("report_bridge")
                module.callAttr("configure_private_directories", privateDirectories.toString())
                val path = module.callAttr("generate_report", parameter.toString()).toString()
                val output = File(path).canonicalFile
                check(output == requestedOutput) {
                    "Python hat nicht den angeforderten PDF-Zielpfad zurückgegeben."
                }
                check(output.isFile && output.length() > 0) { "Die PDF-Datei wurde nicht erzeugt." }
                Ergebnis.Erfolg(output.absolutePath)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: PyException) {
                Ergebnis.Fehler("Bericht konnte nicht erzeugt werden: ${pythonFehlerdetails(error.message)}", error)
            } catch (error: Exception) {
                Ergebnis.Fehler("Bericht konnte nicht erzeugt werden: ${error.message ?: "Dateifehler"}", error)
            }
        }
    }

    companion object {
        private val pythonMutex = Mutex()
    }
}

private val pythonExceptionLine = Regex("""^(?:[A-Za-z_]\w*\.)?[A-Za-z_]\w*(?:Error|Exception):\s*.+$""")

/** Bewahrt bei Importfehlern Python-Typ und Modul; fachliche ValueErrors bleiben nutzerlesbar. */
internal fun pythonFehlerdetails(rawMessage: String?): String {
    val lines = rawMessage.orEmpty().lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    if (lines.isEmpty()) return "Python-Verarbeitung fehlgeschlagen."

    val exceptionLine = lines.asReversed().firstOrNull { pythonExceptionLine.matches(it) }
    val relevant = exceptionLine ?: lines.firstOrNull {
        !it.startsWith("Traceback") && !it.startsWith("File ")
    } ?: lines.first()
    val importLine = lines.asReversed().firstOrNull {
        it.contains("ModuleNotFoundError") ||
            it.contains("ImportError") ||
            it.contains("No module named") ||
            it.contains("report_bridge")
    }
    if (importLine != null) {
        listOf("ModuleNotFoundError", "ImportError").forEach { type ->
            val marker = "$type:"
            val index = importLine.lastIndexOf(marker)
            if (index >= 0) return importLine.substring(index)
        }
        val type = if (importLine.contains("No module named")) "ModuleNotFoundError" else "ImportError"
        return "$type: $importLine"
    }
    val userMessage = listOf("ValueError: ", "RuntimeError: ").firstNotNullOfOrNull { marker ->
        relevant.lastIndexOf(marker).takeIf { it >= 0 }?.let { relevant.substring(it + marker.length) }
    } ?: relevant
    return userMessage.ifBlank { "Python-Verarbeitung fehlgeschlagen." }
}
