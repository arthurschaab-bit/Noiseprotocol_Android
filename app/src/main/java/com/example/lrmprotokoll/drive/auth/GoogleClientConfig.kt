package com.example.lrmprotokoll.drive.auth

/**
 * OAuth-Konfiguration fuer den Drive-Sync (Plan Abschnitt 8.4.3).
 *
 * ⚠ [SERVER_CLIENT_ID] ist ein Platzhalter und funktioniert nicht. Eine echte Web-Client-ID
 * kann nur der Kontoinhaber ueber die Google Cloud Console anlegen (braucht Browser-Zugriff auf
 * ein Google-Konto) - dieser Schritt wurde bewusst offen gelassen (Owner-Entscheidung), der
 * restliche Code ist fertig und wartet nur auf diesen einen Wert.
 *
 * Einrichtung, wenn es soweit ist:
 * 1. Google Cloud Console -> neues oder bestehendes Projekt -> "APIs & Services" ->
 *    "Credentials" -> "Create Credentials" -> "OAuth client ID".
 * 2. Einmal als Typ "Android" mit `applicationId` (aktuell `com.example.lrmprotokoll`, siehe
 *    B-6) und dem SHA-1-Fingerabdruck des Signierschluessels - das autorisiert die App selbst.
 * 3. Einmal als Typ "Web application" - DAS ist die ID, die hier eingetragen wird. Android
 *    braucht die Web-Client-ID, nicht die Android-Client-ID, weil `GetSignInWithGoogleOption`
 *    sie als `serverClientId` verlangt (fuer serverseitig verifizierbare ID-Tokens ausgelegt,
 *    auch wenn diese App keinen eigenen Server hat).
 * 4. Drive API in der Cloud Console aktivieren ("APIs & Services" -> "Library" -> "Google
 *    Drive API" -> "Enable").
 * 5. OAuth-Consent-Screen auf Publishing-Status "In production" setzen (Plan 8.4.3 Warnung:
 *    im Status "Testing" laufen Refresh-Tokens nach 7 Tagen ab). Bei `drive.file` als einzigem
 *    Scope ist dafuer KEINE Google-Verifizierung noetig.
 */
object GoogleClientConfig {
    const val SERVER_CLIENT_ID = "PLATZHALTER_NOCH_NICHT_EINGERICHTET.apps.googleusercontent.com"

    /** Plan 8.4.3: nur dieser eine, nicht-sensible Scope - kein voller `drive`-Zugriff. */
    const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

    val konfiguriert: Boolean
        get() = SERVER_CLIENT_ID != "PLATZHALTER_NOCH_NICHT_EINGERICHTET.apps.googleusercontent.com"
}
