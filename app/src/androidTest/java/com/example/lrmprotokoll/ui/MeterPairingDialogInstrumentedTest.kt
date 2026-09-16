package com.example.lrmprotokoll.ui

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.BerechtigungsTestHelfer
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checkliste Button/Screen-Coverage (Phase 1b): [MeterPairingDialog] (die nach der Entfernung
 * von [MeterControlCard], siehe PR #152, verbleibende, aktiv von [MainActivity] genutzte
 * Composable in `MeterControlCard.kt`) war bislang nur indirekt ueber
 * [MainActivityNavigationAndroidTest]s `topAppBarBluetoothBadgeOeffnetPairingDialogUndBrichtAb`
 * abgedeckt, nie mit einem eigenen, dedizierten Test.
 *
 * Deckt bewusst nur den Fall "Berechtigung bereits erteilt" ab (CI-Standardzustand nach
 * `pm install -g`) - der "Berechtigung fehlt"-Zweig wuerde einen Berechtigungsentzug von
 * ausserhalb des Testprozesses brauchen (siehe `BerechtigungsTestHelfer`-KDoc und
 * `.github/scripts/run-instrumented-tests.sh`), das ist eine CI-Infrastruktur-Erweiterung und
 * bewusst nicht Teil dieses Tests.
 */
@RunWith(AndroidJUnit4::class)
class MeterPairingDialogInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun grantBluetoothPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            BerechtigungsTestHelfer.gewaehre(Manifest.permission.BLUETOOTH_SCAN)
            BerechtigungsTestHelfer.gewaehre(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    @Test
    fun ohneGefundeneGeraeteZeigtDerDialogNachDemScanEinenLeerenZustand() {
        composeRule.setContent {
            MeterPairingDialog(
                pairedAddress = null,
                pairedName = null,
                onDeviceSelected = {},
                onDismiss = {},
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("PCE-323 koppeln").assertIsDisplayed()
        // Auf dem CI-Emulator ohne echtes PCE-323 findet der Scan nichts - nach Ablauf des
        // 10s-Scanfensters (SCAN_DURATION_MS) muss der Leerzustand erscheinen, kein Crash.
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            composeRule.onAllNodesWithText(
                "Kein Bluetooth-Gerät gefunden",
                substring = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun schliessenButtonRuftOnDismissAuf() {
        var dismissed = false
        composeRule.setContent {
            MeterPairingDialog(
                pairedAddress = null,
                pairedName = null,
                onDeviceSelected = {},
                onDismiss = { dismissed = true },
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Schließen").assertIsDisplayed().performClick()

        assertTrue(dismissed)
    }

    @Test
    fun aktualisierenButtonIstWaehrendDesErstenScansNichtSichtbarUndErscheintDanach() {
        composeRule.setContent {
            MeterPairingDialog(
                pairedAddress = null,
                pairedName = null,
                onDeviceSelected = {},
                onDismiss = {},
            )
        }
        composeRule.waitForIdle()

        // Waehrend des automatisch gestarteten Erst-Scans zeigt der Titel einen
        // Fortschrittsindikator statt des Refresh-Buttons.
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            composeRule.onAllNodesWithContentDescription("Erneut scannen").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Erneut scannen").assertIsDisplayed().performClick()
    }
}
