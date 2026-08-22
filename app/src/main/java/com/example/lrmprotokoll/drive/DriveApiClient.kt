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

    /** Legt einen Ordner an und liefert dessen `id`. */
    suspend fun ordnerAnlegen(name: String): Result<String>

    /**
     * Sucht einen Ordner mit [name]. Liefert `null`, wenn kein nicht-gelöschter Ordner mit diesem Namen existiert.
     */
    suspend fun ordnerSuchen(name: String): Result<DriveDatei?>

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

    /** Legt eine neue Datei mit Inhalt an und liefert deren `id`. */
    suspend fun dateiAnlegen(
        name: String,
        ordnerId: String,
        inhalt: ByteArray,
        mimeType: String,
        gzip: Boolean = false,
    ): Result<String>

    /** Ersetzt den Inhalt einer bestehenden Datei - aktualisiert in place (Plan 8.4.4). */
    suspend fun dateiAktualisieren(
        fileId: String,
        inhalt: ByteArray,
        mimeType: String,
        gzip: Boolean = false,
    ): Result<Unit>
}
