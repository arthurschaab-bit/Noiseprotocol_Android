package com.example.lrmprotokoll.meter.ble

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class BluetoothPermissionsTest {

    @Test
    @Config(sdk = [29]) // Android 10 (Huawei P30)
    fun android10BenoetigtLocationBerechtigungUndStandortdienst() {
        val perms = BluetoothPermissions.requiredPermissions()
        assertEquals(1, perms.size)
        assertEquals(android.Manifest.permission.ACCESS_FINE_LOCATION, perms[0])
        assertTrue("Auf Android 10 muss Standortdienst als erforderlich markiert sein", BluetoothPermissions.isLocationRequiredForScan())
    }

    @Test
    @Config(sdk = [34]) // Android 14
    fun android14BenoetigtBluetoothScanUndConnect() {
        val perms = BluetoothPermissions.requiredPermissions()
        assertEquals(2, perms.size)
        assertTrue(perms.contains(android.Manifest.permission.BLUETOOTH_SCAN))
        assertTrue(perms.contains(android.Manifest.permission.BLUETOOTH_CONNECT))
        assertFalse("Auf Android 14 ist GPS-Standortdienst für BLE nicht zwingend erforderlich", BluetoothPermissions.isLocationRequiredForScan())
    }

    @Test
    @Config(sdk = [29])
    fun isLocationEnabledLiefertZustandOhneAbsturz() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val enabled = BluetoothPermissions.isLocationEnabled(context)
        assertNotNull(enabled)
    }
}
