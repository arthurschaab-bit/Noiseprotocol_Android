package com.example.lrmprotokoll.report

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Gemeinsame Ablage- und Teilen-Logik fuer alle Berichtsformate.
 *
 * Vorher hatten [ReportManager.shareFile], [MessreiheExport.teilen] und
 * [PeriodenBerichtExport.teilen] jeweils eine eigene Kopie desselben Ablaufs (FileProvider-Uri,
 * `ACTION_SEND`, Chooser) - mit drei verschiedenen MIME-Typ-Ableitungen, zwei verschiedenen
 * Chooser-Titeln und nur in einer der drei Kopien dem [Intent.FLAG_ACTIVITY_NEW_TASK]. Diese
 * Divergenz war kein Schoenheitsfehler: [ReportManager.shareFile] hat jede Datei, die nicht
 * `.wav` war, als `text/plain` deklariert - also auch das ZIP aus
 * [ReportManager.createZipAndShare]. Empfaengerapps filtern nach MIME-Typ; ein als Text
 * deklariertes ZIP taucht in der Zielauswahl schlicht nicht ueberall auf, wo es hingehoert.
 *
 * Die Ableitung steht als reine Funktion ([mimeTypFuer]) daneben, damit sie ohne Context und
 * ohne Robolectric pruefbar ist - dasselbe Muster wie [pegelEinheit] in [ReportManager].
 */
object BerichtDatei {

    /**
     * Ablageort aller Berichte: der app-eigene externe Speicher, per `file_paths.xml` fuer den
     * FileProvider freigegeben. Bisher stand `context.getExternalFilesDir(null)` wortgleich an
     * vier Stellen; eine spaetere Aenderung des Ablageorts haette sonst alle vier finden muessen.
     */
    fun ordner(context: Context): File? = context.getExternalFilesDir(null)

    /**
     * MIME-Typ anhand der Dateiendung. `application/octet-stream` ist der bewusste Rueckfall:
     * lieber "unbekannter Binaerinhalt" als ein falscher Typ, der die Datei in der Zielauswahl
     * verschwinden laesst oder eine Empfaengerapp sie fehlinterpretieren laesst.
     */
    fun mimeTypFuer(dateiname: String): String = when (dateiname.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "csv" -> "text/csv"
        "txt" -> "text/plain"
        "zip" -> "application/zip"
        "wav" -> "audio/wav"
        "jpg", "jpeg" -> "image/jpeg"
        "mp4" -> "video/mp4"
        else -> "application/octet-stream"
    }

    /**
     * Teilt [datei] ueber den System-Chooser.
     *
     * [Intent.FLAG_ACTIVITY_NEW_TASK] steht hier fuer alle Aufrufer, nicht nur - wie vorher - im
     * [ReportManager]: Ohne das Flag wirft `startActivity` aus einem Nicht-Activity-Context
     * (z.B. Application-Context aus einem Composable heraus) eine [android.util.AndroidRuntimeException].
     * Dass es bisher nur an einer der drei Stellen stand, war eine latente Absturzquelle, keine
     * Absicht.
     */
    /**
     * Oeffnet [datei] in einer passenden App (M11 Etappe B: Beweisvideos abspielen). Bewusst
     * ueber [Intent.ACTION_VIEW] statt eines eingebauten Players - fuer ein MP4 gibt es auf
     * jedem Geraet eine Anwendung, und eine eigene Wiedergabe waere eine Abhaengigkeit ohne
     * Gegenwert.
     *
     * Liefert `false`, wenn keine App den Typ oeffnen kann; der Aufrufer kann das melden,
     * statt dass die Aktion wirkungslos verpufft.
     */
    fun oeffne(context: Context, datei: File): Boolean {
        val uri = FileProvider.getUriForFile(context, "${'$'}{context.packageName}.fileprovider", datei)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeTypFuer(datei.name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    fun teile(context: Context, datei: File, chooserTitel: String = "Teilen über…") {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", datei)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeTypFuer(datei.name)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, chooserTitel).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
