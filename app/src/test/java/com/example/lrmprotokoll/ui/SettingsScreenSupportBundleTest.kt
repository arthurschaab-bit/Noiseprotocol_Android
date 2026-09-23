package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * M12 Schritt 8 (Konzept Aufgabe 2): Schalter fuer automatischen Absturz-Upload und das
 * periodische Gesundheits-Bundle in den Einstellungen ("Diagnose & Systemgesundheit"), plus
 * Anzeige des Drive-Zielordners fuer Support-Bundles.
 *
 * Der Sektionstitel wird ueber `getString(R.string.settings_section_diagnostics)` gesucht statt
 * als Literal - `values-en/strings.xml` uebersetzt genau diesen Schluessel, ein hartkodiertes
 * deutsches Literal faende in einer Testumgebung mit englischem Default-Locale (wie dieser hier)
 * keinen Treffer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenSupportBundleTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun oeffneDiagnoseSektion() {
        composeRule.setContent { SettingsScreen(onBack = {}, initialTab = SettingsTab.START) }
        composeRule.waitForIdle()
        val titel = composeRule.activity.getString(com.example.lrmprotokoll.R.string.settings_section_diagnostics)
        composeRule.onNodeWithText(titel, substring = true).performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun schalterStehenStandardmaessigAnUndDriveOrdnerWirdAngezeigt() {
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        app.container.settingsManager.absturzAutoUploadAktiv = true
        app.container.settingsManager.periodischesGesundheitsBundleAktiv = true
        app.container.settingsManager.driveFolderName = "Lärmprotokoll"

        oeffneDiagnoseSektion()

        composeRule.onNodeWithTag("switch_absturz_auto_upload").performScrollTo().assertIsOn()
        composeRule.onNodeWithTag("switch_periodisches_gesundheits_bundle").performScrollTo().assertIsOn()
        val driveOrdnerText = composeRule.activity.getString(
            com.example.lrmprotokoll.R.string.settings_support_bundle_drive_ordner, "Lärmprotokoll",
        )
        composeRule.onNodeWithText(driveOrdnerText).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun abschaltenDesAbsturzAutoUploadSchaltersPersistiertInDenEinstellungen() {
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        app.container.settingsManager.absturzAutoUploadAktiv = true

        oeffneDiagnoseSektion()

        composeRule.onNodeWithTag("switch_absturz_auto_upload").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("switch_absturz_auto_upload").assertIsOff()
        assertFalse(app.container.settingsManager.absturzAutoUploadAktiv)
    }

    @Test
    fun abschaltenDesPeriodischenBundleSchaltersPersistiertInDenEinstellungen() {
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        app.container.settingsManager.periodischesGesundheitsBundleAktiv = true

        oeffneDiagnoseSektion()

        composeRule.onNodeWithTag("switch_periodisches_gesundheits_bundle").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("switch_periodisches_gesundheits_bundle").assertIsOff()
        assertFalse(app.container.settingsManager.periodischesGesundheitsBundleAktiv)
    }
}
