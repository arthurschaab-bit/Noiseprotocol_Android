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
}
