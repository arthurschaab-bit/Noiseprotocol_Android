package com.example.lrmprotokoll.drive

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Die Ablagestruktur auf Google Drive (Owner-Vorgabe):
 *
 * ```
 * <gewaehlter Ordner>/JJJJMMTT/WAV
 * <gewaehlter Ordner>/JJJJMMTT/Schallmessung
 * <gewaehlter Ordner>/JJJJMMTT/Fotos
 * <gewaehlter Ordner>/JJJJMMTT/Videos
 * <gewaehlter Ordner>/JJJJMMTT/Bericht
 * ```
 *
 * Massgeblich ist das Datum der **Aufnahme**, nicht das des Uploads: Ein Video von gestern
 * gehoert in den Ordner von gestern, auch wenn es erst heute hochgeladen wird - sonst haengt die
 * Ablage davon ab, wann zufaellig WLAN da war.
 */
enum class DriveKategorie(val ordnername: String) {
    WAV("WAV"),
    SCHALLMESSUNG("Schallmessung"),
    FOTOS("Fotos"),
    VIDEOS("Videos"),
    BERICHT("Bericht"),
}

object DriveAblage {

    private val TAGESFORMAT = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US)

    /** Tagesordnername aus einem Zeitpunkt - JJJJMMTT in der Zeitzone des Nutzers. */
    fun tagesordner(zeitpunkt: Instant, zone: ZoneId): String =
        TAGESFORMAT.format(zeitpunkt.atZone(zone))

    fun tagesordner(zeitpunktMs: Long, zone: ZoneId): String =
        tagesordner(Instant.ofEpochMilli(zeitpunktMs), zone)

    /**
     * Der Pfad unterhalb des gewaehlten Ordners, als Segmente. Reine Funktion - der Teil, bei dem
     * ein Fehler dazu fuehrt, dass Dateien im falschen Ordner landen und niemand sie wiederfindet.
     */
    fun pfad(tagesordner: String, kategorie: DriveKategorie): List<String> =
        listOf(tagesordner, kategorie.ordnername)

    /** Nur fuer Anzeige und Diagnose - so, wie der Nutzer den Pfad in Drive sieht. */
    fun anzeigepfad(wurzelname: String, tagesordner: String, kategorie: DriveKategorie): String =
        (listOf(wurzelname) + pfad(tagesordner, kategorie)).joinToString("/")
}

/**
 * Loest die Ordner der Tagesablage auf und legt sie bei Bedarf an.
 *
 * **Warum mit Zwischenspeicher:** Ohne ihn kostete jeder einzelne Upload zwei zusaetzliche
 * API-Aufrufe (Suchen, ggf. Anlegen). Bei einem Sync-Zyklus mit Dutzenden Fotos summiert sich das
 * zu einem Vielfachen des eigentlichen Datenverkehrs und laeuft in Drives Rate-Limit.
 *
 * Der Zwischenspeicher lebt bewusst nur so lange wie die Instanz: Loescht der Nutzer einen Ordner
 * in Drive, ist die gemerkte ID ungueltig - nach dem naechsten Zyklus wird sie ohnehin neu
 * ermittelt. Ein dauerhaft persistierter Baum muesste dagegen aktiv invalidiert werden.
 */
class DriveOrdnerbaum(private val api: DriveApiClient) {

    private val zwischenspeicher = mutableMapOf<String, String>()

    /**
     * Liefert die Ordner-ID fuer `<wurzelId>/<tagesordner>/<kategorie>` und legt fehlende Ebenen
     * an. Schlaegt etwas fehl, wird der Fehler durchgereicht - der Aufrufer laedt dann in dieser
     * Runde nichts hoch, statt die Datei an einer falschen Stelle abzulegen.
     */
    suspend fun ordnerFuer(
        wurzelId: String,
        tagesordner: String,
        kategorie: DriveKategorie,
    ): Result<String> {
        val tagesId = unterordner(wurzelId, tagesordner).getOrElse { return Result.failure(it) }
        return unterordner(tagesId, kategorie.ordnername)
    }

    private suspend fun unterordner(elternId: String, name: String): Result<String> {
        val schluessel = "$elternId/$name"
        zwischenspeicher[schluessel]?.let { return Result.success(it) }

        // Erst suchen, dann anlegen - sonst entstuende bei jedem Zyklus ein weiterer Ordner
        // gleichen Namens. Drive erlaubt das, und der Nutzer haette danach fuenf "Fotos"-Ordner.
        val vorhanden = api.ordnerSuchen(name, elternId).getOrElse { return Result.failure(it) }
        if (vorhanden != null) {
            zwischenspeicher[schluessel] = vorhanden.id
            return Result.success(vorhanden.id)
        }

        return api.ordnerAnlegen(name, elternId).onSuccess { zwischenspeicher[schluessel] = it }
    }

    /** Fuer Tests und den Fall, dass der Nutzer den Zielordner wechselt. */
    fun leereZwischenspeicher() = zwischenspeicher.clear()
}
