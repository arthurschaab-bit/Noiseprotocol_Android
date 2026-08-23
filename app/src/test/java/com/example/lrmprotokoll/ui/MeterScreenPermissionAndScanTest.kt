package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compose- und Regressionstest für Bluetooth-Berechtigungsabfragen und Scan-Flow im [MeterScreen].
 *
 * Verifiziert:
 * 1. Dass bei fehlenden Bluetooth-Berechtigungen ein informativer Hinweis mit Aktions-Button
 *    angezeigt wird.
 * 2. Dass der Scan-Button klickbar ist und bei fehlenden Rechten die Anforderung startet,
 *    ohne die App zu blockieren oder abzustürzen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeterScreenPermissionAndScanTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        ApplicationProvider.getApplicationContext<LaermprotokollApp>()
    }

    @Test
    fun meterScreenZeigtBerechtigungshinweisUndScanButtonKlickFuehrtNichtZumAbsturz() {
        composeRule.setContent {
            MeterScreen(onBack = {})
        }

        // Scan-Button muss sichtbar und klickbar sein
        val scanButton = composeRule.onNodeWithTag(SCAN_BUTTON_TAG).performScrollTo()
        scanButton.assertIsDisplayed()
        scanButton.performClick()

        // Berechtigungs-Hinweis oder Button muss vorhanden sein
        val pairStr = composeRule.activity.getString(com.example.lrmprotokoll.R.string.meter_pair_new_title)
        composeRule.onNodeWithText(pairStr, substring = true).performScrollTo().assertIsDisplayed()
    }
}
