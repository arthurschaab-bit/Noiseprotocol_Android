package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.meter.ConnectionState
import com.example.lrmprotokoll.meter.MeasurementRange
import com.example.lrmprotokoll.meter.MeterFrame
import com.example.lrmprotokoll.meter.TimeWeighting
import com.example.lrmprotokoll.meter.Weighting
import com.example.lrmprotokoll.meter.ble.BleDevice
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Echtes Geraete-Pendant zu [MeterControlCardTest] (Robolectric, app/src/test) - Teil der
 * Bestandsaufnahme nach dem Datumsbereich-Dialog-Bug (Owner-Auftrag 15.09.2026). Die reine
 * Sortierlogik (sortiereGefundeneGeraete) braucht keine Compose-Umgebung und bleibt bewusst ein
 * normaler JVM-Unit-Test in app/src/test - hier nur der Compose-Rendering-Teil.
 */
@RunWith(AndroidJUnit4::class)
class MeterControlCardInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun sortierungBleibtStabilTrotzRssiSchwankungen() {
        val pairedAddress = "AA:BB:CC:DD:EE:01"

        val devicePaired = BleDevice(name = "PCE-323", address = pairedAddress, rssi = -80)
        val deviceNamedA = BleDevice(name = "A-Device", address = "11:22:33:44:55:66", rssi = -50)
        val deviceNamedB = BleDevice(name = "B-Device", address = "22:33:44:55:66:77", rssi = -90)
        val deviceUnnamed = BleDevice(name = null, address = "33:44:55:66:77:88", rssi = -30)

        val list = listOf(deviceUnnamed, deviceNamedB, deviceNamedA, devicePaired)

        val sortiert1 = sortiereGefundeneGeraete(list, pairedAddress)

        assertEquals(devicePaired.address, sortiert1[0].address)
        assertEquals(deviceNamedA.address, sortiert1[1].address)
        assertEquals(deviceNamedB.address, sortiert1[2].address)
        assertEquals(deviceUnnamed.address, sortiert1[3].address)

        val listWithNewRssi = listOf(
            deviceUnnamed.copy(rssi = -95),
            deviceNamedB.copy(rssi = -35),
            deviceNamedA.copy(rssi = -85),
            devicePaired.copy(rssi = -40),
        )

        val sortiert2 = sortiereGefundeneGeraete(listWithNewRssi, pairedAddress)

        assertEquals(sortiert1.map { it.address }, sortiert2.map { it.address })
    }

    @Test
    fun meterControlCardZeigtGetrenntZustandUndKoppelnButton() {
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
        composeRule.onNodeWithText("PCE-323: Nicht verbunden").assertIsDisplayed()
        composeRule.onNodeWithText("Gerät koppeln").assertIsDisplayed()
    }

    @Test
    fun meterControlCardZeigtVerbundenZustandUndLivePegel() {
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

        composeRule.onNodeWithText("PCE-323 #1: Verbunden").assertIsDisplayed()
        composeRule.onNodeWithText("58.4 dB(A)").assertIsDisplayed()
        composeRule.onNodeWithText("Trennen").assertIsDisplayed()
    }
}
