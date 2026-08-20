package com.example.lrmprotokoll.drive.auth

/**
 * OAuth-Konfiguration fuer den Drive-Sync (Plan Abschnitt 8.4.3).
 *
 * [SERVER_CLIENT_ID] ist die vom Owner ueber die Google Cloud Console angelegte Web-Client-ID
 * (siehe Git-Historie fuer den vorherigen Platzhalter-Zustand und die Einrichtungsschritte).
 * Zusaetzlich existiert eine zweite, separate Client-ID vom Typ "Android"
 * (`applicationId` + SHA-1-Fingerabdruck) in derselben Cloud-Console - die wird NICHT hier
 * eingetragen, sie autorisiert nur die App selbst gegenueber Google, taucht im Code nirgends
 * als Wert auf.
 *
 * Falls der Sign-in-Flow trotz eingetragener ID fehlschlaegt, siehe Plan 8.4.3: OAuth-Consent-
 * Screen muss auf Publishing-Status "In production" stehen (im Status "Testing" laufen
 * Refresh-Tokens nach 7 Tagen ab), und die Drive API muss in der Cloud Console aktiviert sein
 * ("APIs & Services" -> "Library" -> "Google Drive API" -> "Enable").
 */
object GoogleClientConfig {
    const val SERVER_CLIENT_ID = "203132357592-hdakvb0cnfo7ob3i9sd6oqh4le56fk8l.apps.googleusercontent.com"

    /** Plan 8.4.3: nur dieser eine, nicht-sensible Scope - kein voller `drive`-Zugriff. */
    const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

    val konfiguriert: Boolean
        get() = SERVER_CLIENT_ID != "PLATZHALTER_NOCH_NICHT_EINGERICHTET.apps.googleusercontent.com"
}
