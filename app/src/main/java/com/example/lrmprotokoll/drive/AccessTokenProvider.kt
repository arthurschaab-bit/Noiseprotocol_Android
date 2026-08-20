package com.example.lrmprotokoll.drive

/**
 * Liefert ein gueltiges Drive-Zugriffstoken. Trennt die eigentliche Sync-Logik (testbar ohne
 * Geraet) von der Google-Anmeldung (nur am Geraet mit echter OAuth-Client-ID pruefbar,
 * siehe [com.example.lrmprotokoll.drive.auth.GoogleSignInAccessTokenProvider]).
 *
 * Der Token wird bewusst NICHT von hier aus persistiert (Plan 8.4.3): er wird vor jedem Upload
 * frisch angefordert und laeuft still ab, solange die Zustimmung besteht.
 */
interface AccessTokenProvider {
    suspend fun holeToken(): Result<String>
}
