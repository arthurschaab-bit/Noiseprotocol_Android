package com.example.lrmprotokoll.foto

import android.content.Context
import com.example.lrmprotokoll.data.DokumentationsFotoDao
import com.example.lrmprotokoll.data.DokumentationsFotoEntity
import com.example.lrmprotokoll.data.FotoKategorie
import com.example.lrmprotokoll.data.SettingsManager
import com.example.lrmprotokoll.diagnose.DiagnosticsReporter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Umfang einer Kategorie, wie in den Einstellungen konfiguriert. */
enum class FotoUmfang { AUS, OPTIONAL, PFLICHT;
    companion object {
        fun vonName(name: String?): FotoUmfang = entries.firstOrNull { it.name == name } ?: OPTIONAL
    }
}

/**
 * Aufnahme, Ablage und Buchfuehrung der Belegfotos (M11 Etappe A).
 *
 * **Robustheitszusage:** Keine Methode dieser Klasse wirft. Kamera nicht vorhanden, Nutzer
 * bricht ab, Speicher voll, Skalierung misslingt, DB-Insert scheitert - jeder dieser Faelle
 * endet in einem `false`/`null` und einem Diagnoseeintrag, nie in einem Fehler Richtung
 * Aufnahmepfad. Dieselbe Garantie wie [com.example.lrmprotokoll.audio.classifySafely]: Eine
 * Nebenfunktion darf die Messung nicht gefaehrden.
 */
class FotoDokumentation(
    private val context: Context,
    private val dao: DokumentationsFotoDao,
    private val settings: SettingsManager,
    private val diagnostics: DiagnosticsReporter,
) {

    /** Ablageordner der Belegfotos - app-eigener externer Speicher, per file_paths.xml freigegeben. */
    fun ordner(): File = File(context.getExternalFilesDir(null), "fotos").apply { runCatching { mkdirs() } }

    /** Zieldatei fuer eine neue Aufnahme. */
    fun neueZieldatei(sessionId: Long, kategorie: FotoKategorie, jetzt: Long = System.currentTimeMillis()): File {
        val stempel = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(jetzt))
        return File(ordner(), dateiname(sessionId, kategorie, stempel))
    }

    /** Welche Kategorien beim Start abgefragt werden - "AUS" erscheint gar nicht erst. */
    fun abzufragendeKategorien(): List<FotoKategorie> = buildList {
        if (umfangFuer(FotoKategorie.MESSAUFBAU) != FotoUmfang.AUS) add(FotoKategorie.MESSAUFBAU)
        if (umfangFuer(FotoKategorie.KALIBRIERUNG) != FotoUmfang.AUS) add(FotoKategorie.KALIBRIERUNG)
    }

    fun umfangFuer(kategorie: FotoKategorie): FotoUmfang = when (kategorie) {
        FotoKategorie.MESSAUFBAU -> FotoUmfang.vonName(settings.fotoDokuMessaufbau)
        FotoKategorie.KALIBRIERUNG -> FotoUmfang.vonName(settings.fotoDokuKalibrierung)
        FotoKategorie.SONSTIGES -> FotoUmfang.OPTIONAL
    }

    /**
     * Uebernimmt ein von der Kamera geschriebenes Foto: verkleinern, drehen, Standortdaten
     * entfernen, Pruefsumme bilden, Zeile anlegen. Liefert die neue Zeilen-ID oder `null`.
     */
    suspend fun uebernehmeAufnahme(
        rohdatei: File,
        sessionId: Long,
        kategorie: FotoKategorie,
        notiz: String? = null,
        jetzt: Long = System.currentTimeMillis(),
    ): Long? {
        return try {
            if (dao.anzahlFuerKategorie(sessionId, kategorie.name) >= settings.fotoDokuMaxProKategorie) {
                diagnostics.breadcrumb(
                    "FotoDoku", "Obergrenze je Kategorie erreicht - Foto verworfen",
                    data = mapOf("kategorie" to kategorie.name, "max" to settings.fotoDokuMaxProKategorie),
                )
                return null
            }

            val ziel = neueZieldatei(sessionId, kategorie, jetzt)
            if (!Bildverarbeitung.verkleinereUndSpeichere(rohdatei, ziel)) {
                diagnostics.breadcrumb(
                    "FotoDoku", "Foto konnte nicht aufbereitet werden",
                    data = mapOf("kategorie" to kategorie.name, "quelle" to rohdatei.name),
                )
                return null
            }
            runCatching { if (rohdatei.absolutePath != ziel.absolutePath) rohdatei.delete() }

            val id = dao.insert(
                DokumentationsFotoEntity(
                    sessionId = sessionId,
                    kategorie = kategorie.name,
                    dateiPfad = ziel.absolutePath,
                    aufgenommenAm = jetzt,
                    notiz = notiz?.takeIf { it.isNotBlank() },
                    pruefsumme = Bildverarbeitung.pruefsumme(ziel),
                )
            )
            diagnostics.breadcrumb(
                "FotoDoku", "Foto aufgenommen",
                data = mapOf(
                    "kategorie" to kategorie.name,
                    "sessionId" to sessionId,
                    "groesseBytes" to ziel.length(),
                ),
            )
            id
        } catch (e: Throwable) {
            diagnostics.breadcrumb(
                "FotoDoku", "Foto konnte nicht gespeichert werden",
                data = mapOf("kategorie" to kategorie.name, "fehler" to e.javaClass.simpleName),
            )
            null
        }
    }

    /** Haelt fest, dass eine Kategorie uebersprungen wurde - besonders relevant bei "PFLICHT". */
    fun meldeUebersprungen(kategorie: FotoKategorie) {
        diagnostics.breadcrumb(
            "FotoDoku", "Foto uebersprungen",
            data = mapOf("kategorie" to kategorie.name, "modus" to umfangFuer(kategorie).name),
        )
    }

    suspend fun fuerSession(sessionId: Long): List<DokumentationsFotoEntity> =
        runCatching { dao.fuerSession(sessionId) }.getOrDefault(emptyList())

    /**
     * Loescht Datei und Zeile. Eine bereits nach Drive hochgeladene Kopie bleibt dort bestehen -
     * ein stilles Fernloeschen ist etwas anderes als ein lokales Aufraeumen und wird vom Nutzer
     * nicht erwartet.
     */
    suspend fun loesche(id: Long) {
        runCatching {
            dao.byId(id)?.let { File(it.dateiPfad).delete() }
            dao.loesche(id)
        }
    }

    companion object {
        /** Als reine Funktion pruefbar - der Name taucht auch in Drive auf. */
        fun dateiname(sessionId: Long, kategorie: FotoKategorie, zeitstempel: String): String =
            "foto_${sessionId}_${kategorie.name.lowercase()}_$zeitstempel.jpg"
    }
}
