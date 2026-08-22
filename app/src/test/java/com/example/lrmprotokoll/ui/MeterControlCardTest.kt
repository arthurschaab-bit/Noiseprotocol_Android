package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.meter.ConnectionState
import com.example.lrmprotokoll.meter.MeasurementRange
import com.example.lrmprotokoll.meter.MeterFrame
import com.example.lrmprotokoll.meter.TimeWeighting
import com.example.lrmprotokoll.meter.Weighting
import com.example.lrmprotokoll.meter.ble.BleDevice
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * Tests für die stabile Sortierung der gefundenen Bluetooth-Geräte (kein Springen)
 * und die Anzeige der [MeterControlCard] auf der Startseite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeterControlCardTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun sortierungBleibtStabilTrotzRssiSchwankungen() {
        val pairedAddress = "AA:BB:CC:DD:EE:01"

        val devicePaired = BleDevice(name = "PCE-323", address = pairedAddress, rssi = -80)
        val deviceNamedA = BleDevice(name = "A-Device", address = "11:22:33:44:55:66", rssi = -50)
        val deviceNamedB = BleDevice(name = "B-Device", address = "22:33:44:55:66:77", rssi = -90)
        val deviceUnnamed = BleDevice(name = null, address = "33:44:55:66:77:88", rssi = -30)

        val list = listOf(
            deviceUnnamed,
            deviceNamedB,
            deviceNamedA,
            devicePaired,
        )

        val sortiert1 = sortiereGefundeneGeraete(list, pairedAddress)

        // 1. Gepinntes Gerät steht an erster Stelle
        assertEquals(devicePaired.address, sortiert1[0].address)
        // 2. Benannte Geräte alphabetisch
        assertEquals(deviceNamedA.address, sortiert1[1].address)
        assertEquals(deviceNamedB.address, sortiert1[2].address)
        // 3. Unbenannte Geräte am Ende
        assertEquals(deviceUnnamed.address, sortiert1[3].address)

        // Simulation von RSSI-Schwankungen (wie bei BLE-Scans üblich)
        val listWithNewRssi = listOf(
            deviceUnnamed.copy(rssi = -95),
            deviceNamedB.copy(rssi = -35),
            deviceNamedA.copy(rssi = -85),
            devicePaired.copy(rssi = -40),
        )

        val sortiert2 = sortiereGefundeneGeraete(listWithNewRssi, pairedAddress)

        // Reihenfolge der Adressen bleibt 100% stabil, die Liste springt nicht
        assertEquals(sortiert1.map { it.address }, sortiert2.map { it.address })
    }

    @Test
    fun meterControlCardZeigtGetrenntZustandUndKoppelnButton() {
        ApplicationProvider.getApplicationContext<LaermprotokollApp>()

        composeRule.setContent {
            MeterControlCard(
                connectionState = ConnectionState.DISCONNECTED,
                pairedAddress = null,
                pairedName = null,
                latestFrame = null,
                onConnect = {},
                onDisconnect = {},
                onOpenPairing = {}
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("PCE-323 Messgerät").assertIsDisplayed()
        composeRule.onNodeWithText("BT: Getrennt").assertIsDisplayed()
        composeRule.onNodeWithText("Gerät koppeln").assertIsDisplayed()
    }

    @Test
    fun meterControlCardZeigtVerbundenZustandUndLivePegel() {
        ApplicationProvider.getApplicationContext<LaermprotokollApp>()

        val dummyFrame = MeterFrame(
            level = 58.4,
            weighting = Weighting.A,
            timeWeighting = TimeWeighting.FAST,
            range = MeasurementRange.RANGE_30_130,
            holdMax = false,
            holdMin = false,
            receivedAt = Instant.now(),
            modeAssumptionConfirmed = true,
        )

        composeRule.setContent {
            MeterControlCard(
                connectionState = ConnectionState.STREAMING,
                pairedAddress = "00:11:22:33:44:55",
                pairedName = "PCE-323 #1",
                latestFrame = dummyFrame,
                onConnect = {},
                onDisconnect = {},
                onOpenPairing = {}
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("PCE-323 #1").assertIsDisplayed()
        composeRule.onNodeWithText("58.4 dB(A)").assertIsDisplayed()
        composeRule.onNodeWithText("Trennen").assertIsDisplayed()
    }
}
