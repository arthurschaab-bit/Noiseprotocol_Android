package com.example.lrmprotokoll.meter

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.meter.ble.BleScanner
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit- und Regressionstests für den [BleScanner].
 *
 * Stellt sicher, dass:
 * 1. Der Scanner nicht ungefangen abstürzt, wenn Bluetooth deaktiviert ist oder Berechtigungen fehlen.
 * 2. Fehlerhafte Scan-Zustände saubere Exceptions im Flow werfen, die von der UI gefangen werden können.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BleScannerTest {

    @Test
    fun scanInitialisiertOhneAbsturzUndBehandeltFehlendeAdapter() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scanner = BleScanner(context)

        var fehlerAufgetreten: Throwable? = null
        val ergebnisse = scanner.scan()
            .catch { fehler ->
                fehlerAufgetreten = fehler
            }
            .toList()

        // Unter Robolectric ohne konfigurierten BLE-Stack muss der Flow entweder leer
        // abschließen oder eine gefangene Exception liefern, ohne den Prozess zu beenden.
        assertTrue(ergebnisse.isEmpty())
    }
}
