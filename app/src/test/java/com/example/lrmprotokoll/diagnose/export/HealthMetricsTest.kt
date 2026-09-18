package com.example.lrmprotokoll.diagnose.export

import com.example.lrmprotokoll.data.ConnectionEventEntity
import com.example.lrmprotokoll.data.ConnectionEventType
import com.example.lrmprotokoll.diagnose.DiagnosticCode
import com.example.lrmprotokoll.diagnose.DiagnosticEvent
import com.example.lrmprotokoll.diagnose.DiagnosticId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M12 Schritt 6 Aufgabe 3 & 5: [berechneHealthMetrics] als reine Funktion gegen Fake-Daten
 * (Konzept: "Kennzahlen-Berechnung als reine Funktion, mit Fake-Daten") und die "Kein Bundle
 * ohne Not"-Entscheidung ([HealthMetrics.istUnveraendert]). Bewusst ohne Robolectric - siehe
 * [HealthMetrics]-KDoc.
 */
class HealthMetricsTest {

    private fun connectionEvent(type: String) =
        ConnectionEventEntity(sessionId = 1L, at = 0L, type = type, reason = null)

    private fun event(code: DiagnosticCode) = DiagnosticEvent(
        diagnosticId = DiagnosticId.random(),
        code = code,
        component = "Test",
        operation = "test",
    )

    @Test
    fun zaehltReconnectsAlsDegradedUndDisconnectedNichtConnectedOderRecovered() {
        val events = listOf(
            connectionEvent(ConnectionEventType.CONNECTED),
            connectionEvent(ConnectionEventType.DEGRADED),
            connectionEvent(ConnectionEventType.DISCONNECTED),
            connectionEvent(ConnectionEventType.RECOVERED),
        )

        val metrics = berechneHealthMetrics(
            connectionEventsSeitLetztemBundle = events,
            diagnosticLogEntryCountSeitLetztemBundle = 0L,
            dbGroesseAktuellBytes = 100L,
            dbGroesseLetztesBundleBytes = 100L,
            heapHochstandBytes = 1_000L,
            eventsSeitLetztemBundle = emptyList(),
        )

        assertEquals(2, metrics.reconnectCount)
    }

    @Test
    fun zaehltDecodeFehlerUndGruppiertAlleFehlerJeCode() {
        val events = listOf(
            event(DiagnosticCode.BLE_DECODE_RATE_HIGH),
            event(DiagnosticCode.BLE_DECODE_RATE_HIGH),
            event(DiagnosticCode.BLE_GATT_TIMEOUT),
        )

        val metrics = berechneHealthMetrics(
            connectionEventsSeitLetztemBundle = emptyList(),
            diagnosticLogEntryCountSeitLetztemBundle = 0L,
            dbGroesseAktuellBytes = 100L,
            dbGroesseLetztesBundleBytes = 100L,
            heapHochstandBytes = 1_000L,
            eventsSeitLetztemBundle = events,
        )

        assertEquals(2, metrics.decodeFehlerCount)
        assertEquals(2, metrics.fehlerJeCode[DiagnosticCode.BLE_DECODE_RATE_HIGH])
        assertEquals(1, metrics.fehlerJeCode[DiagnosticCode.BLE_GATT_TIMEOUT])
    }

    @Test
    fun dbWachstumIstDieDifferenzUndNieNegativ() {
        val gewachsen = berechneHealthMetrics(
            connectionEventsSeitLetztemBundle = emptyList(),
            diagnosticLogEntryCountSeitLetztemBundle = 0L,
            dbGroesseAktuellBytes = 5_000L,
            dbGroesseLetztesBundleBytes = 3_000L,
            heapHochstandBytes = 0L,
            eventsSeitLetztemBundle = emptyList(),
        )
        assertEquals(2_000L, gewachsen.dbWachstumBytes)

        // Eine kleinere DB (z.B. nach Retention-Bereinigung) darf kein negatives "Wachstum" ergeben.
        val geschrumpft = berechneHealthMetrics(
            connectionEventsSeitLetztemBundle = emptyList(),
            diagnosticLogEntryCountSeitLetztemBundle = 0L,
            dbGroesseAktuellBytes = 1_000L,
            dbGroesseLetztesBundleBytes = 3_000L,
            heapHochstandBytes = 0L,
            eventsSeitLetztemBundle = emptyList(),
        )
        assertEquals(0L, geschrumpft.dbWachstumBytes)
    }

    @Test
    fun unveraendertOhneJeglicheAktivitaetErgibtKeinBundle() {
        val metrics = berechneHealthMetrics(
            connectionEventsSeitLetztemBundle = emptyList(),
            diagnosticLogEntryCountSeitLetztemBundle = 0L,
            dbGroesseAktuellBytes = 1_000L,
            dbGroesseLetztesBundleBytes = 1_000L,
            heapHochstandBytes = 999_999L,
            eventsSeitLetztemBundle = emptyList(),
        )

        assertTrue("Ohne Aenderung darf kein Bundle entstehen", metrics.istUnveraendert())
    }

    @Test
    fun einEinzigerReconnectMachtEsVeraendert() {
        val metrics = berechneHealthMetrics(
            connectionEventsSeitLetztemBundle = listOf(connectionEvent(ConnectionEventType.DEGRADED)),
            diagnosticLogEntryCountSeitLetztemBundle = 0L,
            dbGroesseAktuellBytes = 1_000L,
            dbGroesseLetztesBundleBytes = 1_000L,
            heapHochstandBytes = 0L,
            eventsSeitLetztemBundle = emptyList(),
        )

        assertFalse(metrics.istUnveraendert())
    }

    @Test
    fun einZusaetzlicherDiagnoseFehlerMachtEsVeraendertAuchOhneReconnect() {
        val metrics = berechneHealthMetrics(
            connectionEventsSeitLetztemBundle = emptyList(),
            diagnosticLogEntryCountSeitLetztemBundle = 0L,
            dbGroesseAktuellBytes = 1_000L,
            dbGroesseLetztesBundleBytes = 1_000L,
            heapHochstandBytes = 0L,
            eventsSeitLetztemBundle = listOf(event(DiagnosticCode.AUDIO_READ_FAILED)),
        )

        assertFalse(metrics.istUnveraendert())
    }

    @Test
    fun dbWachstumAlleinMachtEsVeraendert() {
        val metrics = berechneHealthMetrics(
            connectionEventsSeitLetztemBundle = emptyList(),
            diagnosticLogEntryCountSeitLetztemBundle = 0L,
            dbGroesseAktuellBytes = 2_000L,
            dbGroesseLetztesBundleBytes = 1_000L,
            heapHochstandBytes = 0L,
            eventsSeitLetztemBundle = emptyList(),
        )

        assertFalse(metrics.istUnveraendert())
    }

    @Test
    fun heapHochstandAlleinMachtEsNichtVeraendert() {
        // Der Heapstand schwankt staendig auch ohne echtes Problem (HealthMetrics-KDoc) - er
        // darf allein kein Bundle ausloesen, sonst greift "Kein Bundle ohne Not" nie.
        val metrics = berechneHealthMetrics(
            connectionEventsSeitLetztemBundle = emptyList(),
            diagnosticLogEntryCountSeitLetztemBundle = 0L,
            dbGroesseAktuellBytes = 1_000L,
            dbGroesseLetztesBundleBytes = 1_000L,
            heapHochstandBytes = 999_999_999L,
            eventsSeitLetztemBundle = emptyList(),
        )

        assertTrue(metrics.istUnveraendert())
    }
}
