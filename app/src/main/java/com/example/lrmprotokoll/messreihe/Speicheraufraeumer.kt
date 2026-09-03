package com.example.lrmprotokoll.messreihe

import android.content.Context
import android.os.StatFs
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Eine Datei im App-Verzeichnis, reduziert auf das, was fuer die Speicherverwaltung zaehlt.
 * Bewusst ohne [File]: So laesst sich die Auswahllogik ohne Dateisystem pruefen.
 */
data class Speicherdatei(val name: String, val bytes: Long, val geaendertAm: Long)

/** Was in einer Kategorie zusammenkommt. */
data class Speicherposten(val kategorie: Speicherkategorie, val anzahl: Int, val bytes: Long)

/**
 * Die vollstaendige Belegung. [sonstigesBytes] ist wichtig fuer die Ehrlichkeit der Anzeige:
 * Ohne diesen Rest wuerden sich die angezeigten Zahlen nicht zur tatsaechlichen Belegung
 * addieren, und der Nutzer suchte den Unterschied vergeblich.
 */
data class Speicherbelegung(
    val posten: List<Speicherposten>,
    val sonstigesAnzahl: Int,
    val sonstigesBytes: Long,
    val datenbankBytes: Long,
    val freiBytes: Long,
) {
    val gesamtBytes: Long get() = posten.sumOf { it.bytes } + sonstigesBytes + datenbankBytes
    fun posten(kategorie: Speicherkategorie): Speicherposten =
        posten.firstOrNull { it.kategorie == kategorie } ?: Speicherposten(kategorie, 0, 0)
}

/** Ergebnis eines Aufraeumlaufs. [fehlgeschlagen] sind Dateien, die sich nicht loeschen liessen. */
data class AufraeumErgebnis(val geloescht: Int, val bytes: Long, val fehlgeschlagen: Int)

/**
 * Die Dateiarten, die die App selbst erzeugt und die der Nutzer gezielt freigeben koennen soll.
 *
 * Berichte und Exporte (PDF/CSV/ZIP) sind bewusst **keine** eigene Kategorie zum Loeschen: Sie
 * sind das Ergebnis der Arbeit, nicht deren Rohmaterial, und fallen groessenmaessig kaum ins
 * Gewicht. Sie erscheinen in der Anzeige unter "Sonstiges", damit die Summe stimmt.
 */
enum class Speicherkategorie(val anzeigename: String, val endungen: Set<String>) {
    AUDIO("Audioaufnahmen (WAV)", setOf("wav")),

    /**
     * Auch `.pcm`: Das ist die Tonspur eines Beweisvideos, die zwischen Aufnahme und Mux-Lauf
     * existiert - und die liegenbleibt, wenn der Mux-Lauf fehlschlug. Sie gehoert zum Video und
     * soll mit ihm freigegeben werden koennen.
     */
    VIDEO("Beweisvideos", setOf("mp4", "pcm")),

    FOTO("Belegfotos", setOf("jpg", "jpeg")),
}

object Speicheraufraeumer {

    /**
     * Dateien, die juenger als diese Frist sind, werden nie geloescht - auch nicht bei
     * "alles loeschen".
     *
     * Der Grund ist kein Komfort, sondern Datenverlust: Waehrend eine Messung laeuft, schreibt
     * der [com.example.lrmprotokoll.audio.AudioRecordingService] gerade an einer Ereignis-WAV,
     * und ein laufender Mux-Lauf haelt MP4 und PCM offen. Ein Aufraeumlauf, der genau dann
     * zuschlaegt, zerstoert die Aufnahme, die der Nutzer gerade macht. Fuenf Minuten decken die
     * laengste Ereignisdauer und einen normalen Mux-Lauf ab.
     */
    const val SCHONFRIST_MS = 5L * 60 * 1000

    fun kategorieFuer(dateiname: String): Speicherkategorie? {
        val endung = dateiname.substringAfterLast('.', "").lowercase()
        if (endung.isEmpty()) return null
        return Speicherkategorie.entries.firstOrNull { endung in it.endungen }
    }

    /**
     * Zaehlt zusammen, was wo liegt. Reine Funktion - das Einlesen des Verzeichnisses macht
     * [ermittleSpeicherbelegung].
     */
    fun fasseZusammen(
        dateien: List<Speicherdatei>,
        datenbankBytes: Long,
        freiBytes: Long,
    ): Speicherbelegung {
        val nachKategorie = dateien.groupBy { kategorieFuer(it.name) }
        val posten = Speicherkategorie.entries.map { kategorie ->
            val gruppe = nachKategorie[kategorie].orEmpty()
            Speicherposten(kategorie, gruppe.size, gruppe.sumOf { it.bytes })
        }
        val sonstiges = nachKategorie[null].orEmpty()
        return Speicherbelegung(
            posten = posten,
            sonstigesAnzahl = sonstiges.size,
            sonstigesBytes = sonstiges.sumOf { it.bytes },
            datenbankBytes = datenbankBytes,
            freiBytes = freiBytes,
        )
    }

    /**
     * Waehlt aus, was ein Aufraeumauftrag tatsaechlich treffen wuerde.
     *
     * @param aelterAlsTage `null` bedeutet "ohne Altersgrenze" - die [SCHONFRIST_MS] gilt
     * trotzdem, sie ist keine Bequemlichkeit, sondern der Schutz der laufenden Aufnahme.
     *
     * Genau diese Funktion liefert auch die Vorschau. Vorschau und Ausfuehrung duerfen nicht aus
     * zwei getrennten Rechnungen stammen - sonst loescht die App etwas anderes, als sie
     * angekuendigt hat.
     */
    fun waehleAus(
        dateien: List<Speicherdatei>,
        kategorien: Set<Speicherkategorie>,
        aelterAlsTage: Int?,
        jetzt: Long,
    ): List<Speicherdatei> {
        if (kategorien.isEmpty()) return emptyList()
        val juengstesErlaubt = jetzt - SCHONFRIST_MS
        val altersgrenze = aelterAlsTage
            ?.takeIf { it > 0 }
            ?.let { jetzt - it * 24L * 60 * 60 * 1000 }

        return dateien.filter { datei ->
            val kategorie = kategorieFuer(datei.name)
            kategorie != null &&
                kategorie in kategorien &&
                datei.geaendertAm <= juengstesErlaubt &&
                (altersgrenze == null || datei.geaendertAm < altersgrenze)
        }
    }
}

/**
 * Liest das App-Verzeichnis ein und ermittelt zusaetzlich Datenbankgroesse und freien Platz.
 *
 * Zaehlt direkt vom Dateisystem statt ueber die Datenbankeintraege - wie
 * [ermittleSpeicherplatz], und aus demselben Grund: Angezeigt werden soll der tatsaechliche
 * Verbrauch, einschliesslich verwaister Dateien, die kein Eintrag mehr kennt.
 */
suspend fun ermittleSpeicherbelegung(context: Context): Speicherbelegung = withContext(Dispatchers.IO) {
    val verzeichnis = context.getExternalFilesDir(null)
    val dateien = verzeichnis
        ?.listFiles { datei -> datei.isFile }
        ?.map { Speicherdatei(it.name, it.length(), it.lastModified()) }
        ?: emptyList()

    val datenbankDatei = context.getDatabasePath("noise_database")
    val datenbankBytes = if (datenbankDatei.exists()) datenbankDatei.length() else 0L
    val frei = runCatching { StatFs(verzeichnis!!.path).availableBytes }.getOrDefault(0L)

    Speicheraufraeumer.fasseZusammen(dateien, datenbankBytes, frei)
}

/** Wie viel ein Auftrag freigeben wuerde - dieselbe Auswahl wie [raeumeSpeicherAuf]. */
suspend fun ermittleAufraeumVorschau(
    context: Context,
    kategorien: Set<Speicherkategorie>,
    aelterAlsTage: Int?,
): Speicherposten = withContext(Dispatchers.IO) {
    val treffer = sammleTreffer(context, kategorien, aelterAlsTage)
    Speicherposten(
        kategorie = kategorien.firstOrNull() ?: Speicherkategorie.AUDIO,
        anzahl = treffer.size,
        bytes = treffer.sumOf { it.bytes },
    )
}

/**
 * Loescht die ausgewaehlten Dateien.
 *
 * **Die Datenbankeintraege bleiben stehen.** Ein Protokolleintrag ist auch ohne seine
 * Audiodatei ein Beleg: Zeitpunkt, Pegel, Klassifikation und Zuordnung zur Session sind das
 * eigentliche Protokoll, die Datei ist die Beilage. Wer Platz freigibt, will Platz freigeben -
 * nicht seine Messreihe verlieren. Die Wiedergabe- und Detailansichten kommen mit einer
 * fehlenden Datei bereits zurecht.
 */
suspend fun raeumeSpeicherAuf(
    context: Context,
    kategorien: Set<Speicherkategorie>,
    aelterAlsTage: Int?,
): AufraeumErgebnis = withContext(Dispatchers.IO) {
    val verzeichnis = context.getExternalFilesDir(null) ?: return@withContext AufraeumErgebnis(0, 0, 0)
    val treffer = sammleTreffer(context, kategorien, aelterAlsTage)

    var geloescht = 0
    var bytes = 0L
    var fehlgeschlagen = 0
    for (eintrag in treffer) {
        val datei = File(verzeichnis, eintrag.name)
        // Groesse vor dem Loeschen merken: Danach liefert length() null.
        val groesse = datei.length()
        if (runCatching { datei.delete() }.getOrDefault(false)) {
            geloescht++
            bytes += groesse
        } else {
            fehlgeschlagen++
        }
    }
    AufraeumErgebnis(geloescht, bytes, fehlgeschlagen)
}

private fun sammleTreffer(
    context: Context,
    kategorien: Set<Speicherkategorie>,
    aelterAlsTage: Int?,
): List<Speicherdatei> {
    val dateien = context.getExternalFilesDir(null)
        ?.listFiles { datei -> datei.isFile }
        ?.map { Speicherdatei(it.name, it.length(), it.lastModified()) }
        ?: emptyList()
    return Speicheraufraeumer.waehleAus(dateien, kategorien, aelterAlsTage, System.currentTimeMillis())
}
