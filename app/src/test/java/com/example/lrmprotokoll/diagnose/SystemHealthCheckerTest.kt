package com.example.lrmprotokoll.diagnose

import com.example.lrmprotokoll.meter.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemHealthCheckerTest {

    @Test
    fun allesErteiltErgibtStatusOk() {
        val params = SystemHealthParams(
            hasAudioPermission = true,
            hasNotificationPermission = true,
            hasBluetoothPermission = true,
            isBatteryOptimizationIgnored = true,
            canScheduleExactAlarms = true,
            isBluetoothAdapterEnabled = true,
            isMeterPinned = true,
            meterConnectionState = ConnectionState.STREAMING,
            isAlertingConfigured = true,
            isDriveSyncConfigured = true,
            isDiagnoseLoggingActive = false,
            isMonitoringActive = true
        )

        val overview = bewerteSystemZustand(params)
        assertEquals(HealthStatus.OK, overview.overallStatus)
        assertFalse(overview.hasProblemWhileMonitoring)
    }

    @Test
    fun fehlendeAudioPermissionErgibtErrorUndProblemBeiAktiverUeberwachung() {
        val params = SystemHealthParams(
            hasAudioPermission = false,
            hasNotificationPermission = true,
            hasBluetoothPermission = true,
            isBatteryOptimizationIgnored = true,
            canScheduleExactAlarms = true,
            isBluetoothAdapterEnabled = true,
            isMeterPinned = false,
            meterConnectionState = ConnectionState.IDLE,
            isAlertingConfigured = false,
            isDriveSyncConfigured = false,
            isDiagnoseLoggingActive = false,
            isMonitoringActive = true
        )

        val overview = bewerteSystemZustand(params)
        assertEquals(HealthStatus.ERROR, overview.overallStatus)
        assertTrue(overview.hasProblemWhileMonitoring)
        assertTrue(overview.items.any { it.id == "audio_perm" && it.status == HealthStatus.ERROR })
    }

    @Test
    fun akkuOptimierungAktivErgibtWarning() {
        val params = SystemHealthParams(
            hasAudioPermission = true,
            hasNotificationPermission = true,
            hasBluetoothPermission = true,
            isBatteryOptimizationIgnored = false,
            canScheduleExactAlarms = true,
            isBluetoothAdapterEnabled = true,
            isMeterPinned = false,
            meterConnectionState = ConnectionState.IDLE,
            isAlertingConfigured = false,
            isDriveSyncConfigured = false,
            isDiagnoseLoggingActive = false,
            isMonitoringActive = false
        )

        val overview = bewerteSystemZustand(params)
        assertEquals(HealthStatus.WARNING, overview.overallStatus)
        assertFalse(overview.hasProblemWhileMonitoring)
    }
}
