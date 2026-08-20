package com.example.lrmprotokoll.diagnose

/**
 * Bereinigt vertrauliche Daten und PII gemaess DSGVO und Konzept Abschnitt 8.
 *
 * Verhindert, dass Tokens, Passwoerter, MAC-Adressen, vollstaendige Pfade, ntfy-Topics,
 * Heartbeat-Ping-URLs oder E-Mails/Google-Konten in Sentry oder Export-Dateien gelangen.
 */
object DiagnosticRedactor {

    private val SENSITIVE_KEYS = setOf(
        "token", "secret", "authorization", "password", "apikey", "api_key", "private_key",
        "topic", "heartbeat", "bearer", "credential", "auth", "refresh", "access_token",
        "ping_url", "heartbeat_url"
    )

    private val MAC_PATTERN = Regex("(?i)([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})")
    private val EMAIL_PATTERN = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
    private val URL_WITH_QUERY_PATTERN = Regex("https?://[^\\s]+[?#][^\\s]*")
    private val FILE_PATH_PATTERN = Regex("(?:[A-Za-z]:\\\\[^\\s:\"'<>|?*]+|(?<![/a-zA-Z0-9_.-])/(?:[a-zA-Z0-9_.-]+/)+[a-zA-Z0-9_.-]+)")

    /**
     * Bereinigt einen beliebigen Freitext-String von sensiblen Mustern.
     */
    fun redactString(input: String?): String? {
        if (input == null) return null
        var result = input

        // MAC-Adressen kuerzen auf Prefix (z. B. "AA:BB:CC:XX:XX:XX")
        result = MAC_PATTERN.replace(result) { match ->
            val mac = match.value
            val parts = if (mac.contains(":")) mac.split(":") else mac.split("-")
            if (parts.size == 6) {
                "${parts[0]}:${parts[1]}:${parts[2]}:XX:XX:XX"
            } else {
                "[REDACTED_MAC]"
            }
        }

        // E-Mails maskieren
        result = EMAIL_PATTERN.replace(result, "[REDACTED_EMAIL]")

        // URLs mit Parametern/Cap-Tokens auf Host + Pfadkuerzung reduzieren
        result = URL_WITH_QUERY_PATTERN.replace(result) { match ->
            val rawUrl = match.value
            val clean = rawUrl.substringBefore("?").substringBefore("#")
            "$clean?[REDACTED_PARAMS]"
        }

        // Absolute Pfade kuerzen auf Dateinamen (z. B. ".../recording.wav")
        result = FILE_PATH_PATTERN.replace(result) { match ->
            val path = match.value
            val filename = path.substringAfterLast("/").substringAfterLast("\\")
            if (filename.isNotEmpty() && !filename.startsWith(".../")) {
                ".../$filename"
            } else {
                path
            }
        }

        return result
    }

    /**
     * Bereinigt eine Map von Schluesseln und Werten.
     */
    fun redactMap(map: Map<String, Any?>): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        for ((key, value) in map) {
            val lowerKey = key.lowercase()
            if (SENSITIVE_KEYS.any { lowerKey.contains(it) }) {
                result[key] = "[REDACTED]"
            } else {
                result[key] = when (value) {
                    is String -> redactString(value)
                    is Map<*, *> -> {
                        @Suppress("UNCHECKED_CAST")
                        redactMap(value as Map<String, Any?>)
                    }
                    is List<*> -> value.map { item ->
                        if (item is String) redactString(item) else item
                    }
                    else -> value
                }
            }
        }
        return result
    }

    /**
     * Bereinigt ein [DiagnosticEvent].
     */
    fun redactEvent(event: DiagnosticEvent): DiagnosticEvent {
        val redactedMessage = redactString(event.message)
        val redactedDetails = redactMap(event.details)
        return event.copy(
            message = redactedMessage,
            details = redactedDetails
        )
    }

    /**
     * Bereinigt eine [DiagnosticBreadcrumb].
     */
    fun redactBreadcrumb(breadcrumb: DiagnosticBreadcrumb): DiagnosticBreadcrumb {
        val redactedMessage = redactString(breadcrumb.message) ?: ""
        val redactedData = redactMap(breadcrumb.data)
        return breadcrumb.copy(
            message = redactedMessage,
            data = redactedData
        )
    }
}
