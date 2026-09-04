package com.example.lrmprotokoll.drive

/**
 * Ergebnis eines Drive-API-Aufrufs, das den HTTP-Status foerdert, statt ihn in einer
 * Exception-Nachricht zu verstecken - der [DriveSyncWorker] muss 401/403/404 unterschiedlich
 * behandeln (Plan Abschnitt 8.4.6) und braucht dafuer den Code, nicht nur "es ging schief".
 */
class DriveApiException(message: String, val httpCode: Int? = null, cause: Throwable? = null) :
    Exception(message, cause)

/** Metadaten einer gefundenen Datei - fuer die Dedup-Pruefung aus Plan 8.4.4 reicht die ID. */
data class DriveDatei(val id: String, val name: String)

/**
 * Duenne Schicht ueber die Drive-v3-REST-API - bewusst kein vollstaendiges Google-API-Client-SDK
 * (`google-api-services-drive`), das seinerseits einen HTTP-Client, JSON-Mapping und eigene
 * Auth-Objekte mitbringt. Vier Operationen reichen fuer Plan 8.4 vollstaendig aus, und die duenne
 * Schicht laesst sich mit [okhttp3.mockwebserver.MockWebServer] gegen einen echten HTTP-Server
 * testen (wie [com.example.lrmprotokoll.alert.ntfy.NtfyAlertChannel]), statt nur gegen einen
 * Fake-Client.
 */
interface DriveApiClient {

    /**
     * Legt einen Ordner an und liefert dessen `id`.
     *
     * [elternId] `null` legt ihn auf oberster Ebene an - das ist der Fall bei der Einrichtung.
     * Fuer die Unterordner der Tagesablage (siehe [DriveAblage]) wird der Elternordner
     * mitgegeben, sonst landeten sie alle nebeneinander in "Meine Ablage".
     */
    suspend fun ordnerAnlegen(name: String, elternId: String? = null): Result<String>

    /**
     * Sucht einen Ordner mit [name]. Liefert `null`, wenn kein nicht-gelöschter Ordner mit diesem
     * Namen existiert. [elternId] grenzt die Suche auf einen Elternordner ein - ohne das faende
     * die Suche nach "Fotos" jeden gleichnamigen Ordner im ganzen Laufwerk.
     */
    suspend fun ordnerSuchen(name: String, elternId: String? = null): Result<DriveDatei?>

    /**
     * Listet alle für die App erreichbaren Ordner auf Google Drive auf.
     */
    suspend fun ordnerAuflisten(): Result<List<DriveDatei>>

    /**
     * Benennt den Ordner mit [ordnerId] in [neuerName] um.
     */
    suspend fun ordnerUmbenennen(ordnerId: String, neuerName: String): Result<Unit>

    /**
     * Sucht eine Datei mit [name] in [ordnerId]. Liefert `null`, wenn keine existiert - das ist
     * die Absicherung gegen Waisen aus Plan 8.4.4: vor jedem `create` muss geprueft werden, ob
     * ein vorheriger, teilweise fehlgeschlagener Versuch bereits eine Datei angelegt hat.
     */
    suspend fun dateiSuchen(name: String, ordnerId: String): Result<DriveDatei?>

    /**
     * Listet alle Dateinamen in [ordnerId] in einer einzigen Abfrage auf, um API-Quota zu schonen.
     */
    suspend fun dateienInOrdnerAuflisten(ordnerId: String): Result<Set<String>>

    /** Legt eine neue Datei mit Inhalt an und liefert deren `id`. */
    suspend fun dateiAnlegen(
        name: String,
        ordnerId: String,
        inhalt: ByteArray,
        mimeType: String,
        gzip: Boolean = false,
    ): Result<String>

    /**
     * Laedt [datei] per resumable Upload hoch (Drive v3, `uploadType=resumable`) und liefert die
     * Datei-ID.
     *
     * Anders als [dateiAnlegen] wird die Datei **nie vollstaendig in den Speicher geladen**.
     * [dateiAnlegen] nimmt ein `ByteArray`, und der Multipart-Body materialisiert es ein zweites
     * Mal - bei einem 200-MB-Video sind das ~400 MB Spitzenspeicher und damit ein sicherer
     * `OutOfMemoryError`, kein Randfall. Genau deshalb existiert dieser zweite Weg additiv
     * neben dem bestehenden, statt ihn zu ersetzen: Fuer die kleinen CSV- und JSON-Dateien des
     * taeglichen Syncs ist der einfache Weg weiterhin der richtige.
     *
     * [fortsetzenAb] ist ein frueher erhaltener Session-URI. Ist er gesetzt, wird zuerst der
     * Serverstand abgefragt und ab dort weitergemacht. Antwortet der Server darauf mit `404`
     * (ein Session-URI ist nur etwa eine Woche gueltig), beginnt der Upload **einmal** von vorn -
     * nicht endlos wiederholt.
     *
     * [sessionGestartet] wird mit dem neuen Session-URI aufgerufen, **bevor** der erste
     * Datenblock rausgeht. Ohne diesen Rueckruf koennte der Aufrufer den URI nicht persistieren,
     * und ein Prozess-Neustart mitten im Upload finge wieder bei null an - das ist der Grund,
     * warum die Signatur hier ueber die Skizze in `docs/PROMPT_M11_FOTO_VIDEO.md` B.6
     * hinausgeht.
     *
     * [fortschritt] meldet nach jedem Block die vom **Server bestaetigten** Bytes - nicht die,
     * die gesendet wurden.
     */
    suspend fun dateiHochladenResumable(
        name: String,
        ordnerId: String,
        datei: java.io.File,
        mimeType: String,
        fortsetzenAb: String? = null,
        sessionGestartet: suspend (sessionUri: String) -> Unit = {},
        fortschritt: suspend (bestaetigt: Long, gesamt: Long) -> Unit = { _, _ -> },
    ): Result<String>

    /** Ersetzt den Inhalt einer bestehenden Datei - aktualisiert in place (Plan 8.4.4). */
    suspend fun dateiAktualisieren(
        fileId: String,
        inhalt: ByteArray,
        mimeType: String,
        gzip: Boolean = false,
    ): Result<Unit>
}
