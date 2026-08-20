package com.example.lrmprotokoll.diagnose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DiagnosticFingerprintTest {

    @Test
    fun generatesDeterministicFingerprint() {
        val fp1 = DiagnosticFingerprint.create(
            code = DiagnosticCode.BLE_CONNECT_FAILED,
            component = "BleMeterTransport",
            operation = "connectGatt",
            causeClass = "java.io.IOException",
            statusCode = "133"
        )

        val fp2 = DiagnosticFingerprint.create(
            code = DiagnosticCode.BLE_CONNECT_FAILED,
            component = "BleMeterTransport",
            operation = "connectGatt",
            causeClass = "java.io.IOException",
            statusCode = "133"
        )

        assertEquals("BLE_CONNECT_FAILED:blemetertransport:connectgatt:java.io.IOException:133", fp1)
        assertEquals(fp1, fp2)
    }

    @Test
    fun normalizesCasingAndWhitespace() {
        val fp1 = DiagnosticFingerprint.create(
            code = DiagnosticCode.DB_WRITE_FAILED,
            component = " NoiseDao ",
            operation = "Insert ",
            causeClass = "android.database.sqlite.SQLiteDiskIOException",
            statusCode = null
        )

        val fp2 = DiagnosticFingerprint.create(
            code = DiagnosticCode.DB_WRITE_FAILED,
            component = "noisedao",
            operation = "insert",
            causeClass = "android.database.sqlite.SQLiteDiskIOException",
            statusCode = null
        )

        assertEquals(fp1, fp2)
    }

    @Test
    fun differentErrorsProduceDifferentFingerprints() {
        val fp1 = DiagnosticFingerprint.create(
            code = DiagnosticCode.BLE_GATT_ERROR,
            component = "BleMeterTransport",
            operation = "read",
            causeClass = "IllegalStateException",
            statusCode = "133"
        )

        val fp2 = DiagnosticFingerprint.create(
            code = DiagnosticCode.BLE_GATT_ERROR,
            component = "BleMeterTransport",
            operation = "read",
            causeClass = "IllegalStateException",
            statusCode = "257"
        )

        assertNotEquals(fp1, fp2)
    }
}
