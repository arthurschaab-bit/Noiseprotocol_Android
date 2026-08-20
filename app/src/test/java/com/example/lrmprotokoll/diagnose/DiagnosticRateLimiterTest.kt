package com.example.lrmprotokoll.diagnose

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticRateLimiterTest {

    private var currentTime = Instant.parse("2026-08-20T10:00:00Z")

    private val rateLimiter = DiagnosticRateLimiter(
        windowDuration = Duration.ofMinutes(15),
        maxPerFingerprintPerSession = 5,
        timeProvider = { currentTime }
    )

    private fun createEvent(
        code: DiagnosticCode = DiagnosticCode.BLE_DECODE_RATE_HIGH,
        severity: DiagnosticSeverity = DiagnosticSeverity.WARN,
        handled: Boolean = true,
    ): DiagnosticEvent {
        return DiagnosticEvent(
            code = code,
            severity = severity,
            handled = handled,
            component = "Pce323Decoder",
            operation = "decode",
            fingerprint = "BLE_DECODE_RATE_HIGH:pce323decoder:decode:none:none"
        )
    }

    @Test
    fun firstEventIsEmittedImmediately() {
        val event = createEvent()
        val result = rateLimiter.checkAndRecord(event)

        assertEquals(DiagnosticRateLimiter.Decision.EMIT_NOW, result.decision)
        assertEquals(1, result.occurrenceCount)
    }

    @Test
    fun immediateDuplicatesAreSuppressedAndCounted() {
        val event = createEvent()

        val r1 = rateLimiter.checkAndRecord(event)
        assertEquals(DiagnosticRateLimiter.Decision.EMIT_NOW, r1.decision)

        val r2 = rateLimiter.checkAndRecord(event)
        assertEquals(DiagnosticRateLimiter.Decision.SUPPRESS_AND_COUNT, r2.decision)
        assertEquals(2, r2.occurrenceCount)

        val r3 = rateLimiter.checkAndRecord(event)
        assertEquals(DiagnosticRateLimiter.Decision.SUPPRESS_AND_COUNT, r3.decision)
        assertEquals(3, r3.occurrenceCount)
    }

    @Test
    fun eventAfterWindowIsEmittedWithAggregatedCount() {
        val event = createEvent()

        rateLimiter.checkAndRecord(event) // 1 - emit
        rateLimiter.checkAndRecord(event) // 2 - suppress
        rateLimiter.checkAndRecord(event) // 3 - suppress

        // Zeit um 16 Minuten vorspulen
        currentTime = currentTime.plus(Duration.ofMinutes(16))

        val r4 = rateLimiter.checkAndRecord(event) // 4 - emit aggregated count (2 suppressed + 1 = 3)
        assertEquals(DiagnosticRateLimiter.Decision.EMIT_NOW, r4.decision)
        assertEquals(3, r4.occurrenceCount)
    }

    @Test
    fun eventsExceedingMaxLimitAreDropped() {
        val event = createEvent()

        repeat(5) {
            rateLimiter.checkAndRecord(event)
        }

        // 6th event exceeds maxPerFingerprintPerSession (5)
        val r6 = rateLimiter.checkAndRecord(event)
        assertEquals(DiagnosticRateLimiter.Decision.DROP_LIMIT_REACHED, r6.decision)
    }

    @Test
    fun fatalAndUnhandledErrorsAreNeverSuppressedOrDropped() {
        val fatalEvent = createEvent(
            code = DiagnosticCode.APP_UNCAUGHT,
            severity = DiagnosticSeverity.FATAL,
            handled = false
        )

        repeat(10) {
            val result = rateLimiter.checkAndRecord(fatalEvent)
            assertEquals(DiagnosticRateLimiter.Decision.EMIT_NOW, result.decision)
            assertEquals(1, result.occurrenceCount)
        }
    }
}
