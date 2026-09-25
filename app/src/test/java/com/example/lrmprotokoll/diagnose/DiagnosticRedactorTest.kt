package com.example.lrmprotokoll.diagnose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticRedactorTest {

    @Test
    fun redactsMacAddressesToPrefix() {
        val input = "Connected to 00:1A:2B:3C:4D:5E with RSSI -60"
        val redacted = DiagnosticRedactor.redactString(input)
        assertEquals("Connected to 00:1A:2B:XX:XX:XX with RSSI -60", redacted)
    }

    @Test
    fun redactsEmailAddresses() {
        val input = "User account john.doe@example.com logged in"
        val redacted = DiagnosticRedactor.redactString(input)
        assertEquals("User account [REDACTED_EMAIL] logged in", redacted)
    }

    @Test
    fun redactsUrlsWithQueryParameters() {
        val input = "Ping sent to https://hc-ping.com/1234-uuid-secret?state=up#section"
        val redacted = DiagnosticRedactor.redactString(input)
        assertEquals("Ping sent to https://hc-ping.com/1234-uuid-secret?[REDACTED_PARAMS]", redacted)
    }

    @Test
    fun redactsAbsoluteFilePathsToFilename() {
        val linuxPath = "File saved at /data/user/0/com.example.lrmprotokoll/files/record_123.wav"
        val redactedLinux = DiagnosticRedactor.redactString(linuxPath)
        assertEquals("File saved at .../record_123.wav", redactedLinux)

        val winPath = "C:\\Users\\user\\AppData\\Local\\temp\\test.json"
        val redactedWin = DiagnosticRedactor.redactString(winPath)
        assertEquals(".../test.json", redactedWin)
    }

    @Test
    fun redactsSensitiveKeysInMaps() {
        val data = mapOf(
            "token" to "secret_bearer_12345",
            "password" to "my_password",
            "authorization" to "Bearer 987654",
            "heartbeat_url" to "https://hc-ping.com/secret",
            "topic" to "super-secret-ntfy-topic",
            "deviceModel" to "Pixel 8",
            "normalKey" to "Normal value with /var/log/app.log and test@mail.com",
            "nested" to mapOf(
                "apiKey" to "AIzaSySecret",
                "status" to "OK"
            )
        )

        val redacted = DiagnosticRedactor.redactMap(data)

        assertEquals("[REDACTED]", redacted["token"])
        assertEquals("[REDACTED]", redacted["password"])
        assertEquals("[REDACTED]", redacted["authorization"])
        assertEquals("[REDACTED]", redacted["heartbeat_url"])
        assertEquals("[REDACTED]", redacted["topic"])
        assertEquals("Pixel 8", redacted["deviceModel"])

        val normalVal = redacted["normalKey"] as String
        assertTrue(normalVal.contains(".../app.log"))
        assertTrue(normalVal.contains("[REDACTED_EMAIL]"))

        @Suppress("UNCHECKED_CAST")
        val nested = redacted["nested"] as Map<String, Any?>
        assertEquals("[REDACTED]", nested["apiKey"])
        assertEquals("OK", nested["status"])
    }

    /**
     * Geraetefund (BEFUNDE_P30_2026-09-23.md Abschnitt 4, PROMPT_FIX_BUNDLE_INHALT.md Teil 1):
     * "google_account_name" (Klarname aus Google-Sign-In, SettingsManager) fehlte in
     * SENSITIVE_KEYS, waehrend die gepaarte "google_account_email" bereits ueber EMAIL_PATTERN
     * geschwaerzt wurde. "drive_folder_name" ist bewusst als unverdaechtiger Schluessel dabei,
     * um zu zeigen, dass der neue Eintrag ("account_name") gezielt trifft statt breit zu matchen.
     */
    @Test
    fun redactsGoogleAccountDisplayNameButKeepsUnrelatedNameKey() {
        val data = mapOf(
            "google_account_name" to "Max Mustermann",
            "drive_folder_name" to "Laermprotokolle 2026",
        )

        val redacted = DiagnosticRedactor.redactMap(data)

        assertEquals("[REDACTED]", redacted["google_account_name"])
        assertEquals("Laermprotokolle 2026", redacted["drive_folder_name"])
    }

    @Test
    fun redactsDiagnosticEventAndBreadcrumb() {
        val event = DiagnosticEvent(
            code = DiagnosticCode.AUDIO_FILE_WRITE_FAILED,
            component = "AudioRecorder",
            operation = "save",
            message = "Failed writing to /data/data/com.example.lrmprotokoll/cache/audio.wav for user@test.com",
            details = mapOf("token" to "abc", "size" to 1024)
        )

        val cleanEvent = DiagnosticRedactor.redactEvent(event)
        assertTrue(cleanEvent.message!!.contains(".../audio.wav"))
        assertTrue(cleanEvent.message!!.contains("[REDACTED_EMAIL]"))
        assertFalse(cleanEvent.message!!.contains("user@test.com"))
        assertEquals("[REDACTED]", cleanEvent.details["token"])
        assertEquals(1024, cleanEvent.details["size"])

        val breadcrumb = DiagnosticBreadcrumb(
            category = "BLE",
            message = "Discovered device AA:BB:CC:11:22:33",
            data = mapOf("authToken" to "xyz")
        )

        val cleanBreadcrumb = DiagnosticRedactor.redactBreadcrumb(breadcrumb)
        assertEquals("Discovered device AA:BB:CC:XX:XX:XX", cleanBreadcrumb.message)
        assertEquals("[REDACTED]", cleanBreadcrumb.data["authToken"])
    }

    /** M12 Schritt 4 Aufgabe 4: OkHttp-Zeilen mit Authorization-Header. */
    @Test
    fun redactsAuthorizationHeaderLines() {
        val input = "--> GET https://www.googleapis.com/drive/v3/files\nAuthorization: Bearer ya29.a0AfH6SMC_verylongtoken1234\nContent-Type: application/json"
        val redacted = DiagnosticRedactor.redactString(input)!!

        assertTrue(redacted.contains("Authorization: [REDACTED]"))
        assertFalse(redacted.contains("ya29.a0AfH6SMC"))
        // Die Folgezeile darf nicht mitgeloescht werden - ".*" matcht kein "\n".
        assertTrue(redacted.contains("Content-Type: application/json"))
    }

    /** M12 Schritt 4 Aufgabe 4: ein Bearer-Token ohne "Authorization:"-Praefix. */
    @Test
    fun redactsStandaloneBearerToken() {
        val input = "Token im Log: Bearer secretTokenValue123"
        val redacted = DiagnosticRedactor.redactString(input)
        assertEquals("Token im Log: Bearer [REDACTED]", redacted)
    }

    /**
     * M12 Schritt 4 Akzeptanzkriterium (Owner-Entscheidung O-2): weil Logcat jetzt vollstaendig
     * ins Bundle geht, ist dieser Test die eigentliche Schutzschicht, keine Formalie - ein
     * realistischer, mehrzeiliger Ausschnitt mit OkHttp-Authorization-Header, BLE-Scanergebnis
     * mit MAC, Drive-Pfaden und einer Google-Konto-Adresse.
     */
    @Test
    fun redactsRealistischenMehrzeiligenLogcatAusschnitt() {
        val logcat = """
            09-17 12:00:01.123  1234  1234 D OkHttp  : --> POST https://www.googleapis.com/upload/drive/v3/files
            09-17 12:00:01.124  1234  1234 D OkHttp  : Authorization: Bearer ya29.a0AfH6SMC_geheimesToken9876543210
            09-17 12:00:01.200  1234  1234 D BleMeterTransport: Scan result: device AA:BB:CC:11:22:33 rssi=-58
            09-17 12:00:02.001  1234  1234 I DriveSync: Uploading /storage/emulated/0/Android/data/com.example.lrmprotokoll/files/2026-09-17/WAV/event.wav
            09-17 12:00:02.500  1234  1234 W GoogleAuth: signed in as arthur.schaab@googlemail.com
        """.trimIndent()

        val redacted = DiagnosticRedactor.redactString(logcat)!!

        assertFalse(redacted.contains("ya29.a0AfH6SMC"))
        assertTrue(redacted.contains("Authorization: [REDACTED]"))
        assertFalse(redacted.contains("AA:BB:CC:11:22:33"))
        assertTrue(redacted.contains("AA:BB:CC:XX:XX:XX"))
        assertFalse(redacted.contains("/storage/emulated/0/Android/data/com.example.lrmprotokoll/files/2026-09-17/WAV/event.wav"))
        assertTrue(redacted.contains(".../event.wav"))
        assertFalse(redacted.contains("arthur.schaab@googlemail.com"))
        assertTrue(redacted.contains("[REDACTED_EMAIL]"))
    }
}
