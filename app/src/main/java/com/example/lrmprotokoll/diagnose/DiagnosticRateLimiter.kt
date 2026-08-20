package com.example.lrmprotokoll.diagnose

import java.time.Duration
import java.time.Instant

/**
 * Begrenzt und buendelt Fehlerereignisse zum Schutz vor Meldungsstuermen (Konzept Abschnitt 9).
 *
 * - FATAL und unbehandelte Abstuerze passieren immer sofort (kein Drop).
 * - Neuer Fingerprint: Erstes Ereignis sofort.
 * - Wiederholungen innerhalb [windowDuration] erhoehen den Zaehler und werden spaeter aggregiert weitergegeben.
 * - Maximal [maxPerFingerprintPerSession] Ereignisse pro Fingerprint je App-Sitzung.
 */
class DiagnosticRateLimiter(
    private val windowDuration: Duration = Duration.ofMinutes(15),
    private val maxPerFingerprintPerSession: Int = 20,
    private val timeProvider: () -> Instant = { Instant.now() },
) {
    private val lock = Any()

    private data class Entry(
        var firstSeen: Instant,
        var lastEmitted: Instant,
        var totalCount: Int,
        var pendingDuplicates: Int,
    )

    private val entries = mutableMapOf<String, Entry>()

    enum class Decision {
        EMIT_NOW,
        SUPPRESS_AND_COUNT,
        DROP_LIMIT_REACHED
    }

    data class RateLimitResult(
        val decision: Decision,
        val occurrenceCount: Int,
    )

    fun checkAndRecord(event: DiagnosticEvent): RateLimitResult {
        // FATAL Fehler und unbehandelte Abstuerze duerfen niemals verworfen werden
        if (event.severity == DiagnosticSeverity.FATAL || !event.handled) {
            return RateLimitResult(Decision.EMIT_NOW, 1)
        }

        val fingerprint = event.fingerprint.ifEmpty { DiagnosticFingerprint.fromEvent(event) }
        val now = timeProvider()

        synchronized(lock) {
            val entry = entries[fingerprint]
            if (entry == null) {
                entries[fingerprint] = Entry(
                    firstSeen = now,
                    lastEmitted = now,
                    totalCount = 1,
                    pendingDuplicates = 0,
                )
                return RateLimitResult(Decision.EMIT_NOW, 1)
            }

            entry.totalCount++

            if (entry.totalCount > maxPerFingerprintPerSession) {
                return RateLimitResult(Decision.DROP_LIMIT_REACHED, entry.totalCount)
            }

            val elapsedSinceLastEmit = Duration.between(entry.lastEmitted, now)
            if (elapsedSinceLastEmit >= windowDuration) {
                val countToEmit = entry.pendingDuplicates + 1
                entry.pendingDuplicates = 0
                entry.lastEmitted = now
                return RateLimitResult(Decision.EMIT_NOW, countToEmit)
            } else {
                entry.pendingDuplicates++
                return RateLimitResult(Decision.SUPPRESS_AND_COUNT, entry.totalCount)
            }
        }
    }

    fun reset() {
        synchronized(lock) {
            entries.clear()
        }
    }
}
