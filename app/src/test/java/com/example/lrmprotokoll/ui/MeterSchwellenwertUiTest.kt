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
import com.example.lrmprotokoll.R
import org.junit.Rule
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.junit.runner.RunWith

/**
 * Prüfprotokoll vom 11.09.2026, Frage 4 (Korrekturliste C-3): der eigene Schwellwert fürs
 * Messgerät existierte zunächst nur in SettingsManager/MeterTriggerSource - ohne diese Regler
 * hier wäre er für den Nutzer permanent unerreichbar und für immer am Default (60/45 dB)
 * geblieben, obwohl die Datenschicht ihn schon unabhängig vom Mikrofonwert auswertet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h480dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeterSchwellenwertUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun derMessgeraetSchwellenwertTagIstNebenDemMikrofonReglerErreichbar() {
        ApplicationProvider.getApplicationContext<LaermprotokollApp>()

        composeRule.setContent { SettingsScreen(onBack = {}) }

        val titel = composeRule.activity.getString(R.string.settings_section_thresholds)
        composeRule.onNodeWithText(titel, substring = true).performClick()

        composeRule.onNodeWithTag("slider_meter_db_threshold").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun derMessgeraetRuhezeitSchwellenwertIstBeiAktivenRuhezeitenErreichbar() {
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        app.container.settingsManager.quietHoursEnabled = true

        composeRule.setContent { SettingsScreen(onBack = {}) }

        val titel = composeRule.activity.getString(R.string.settings_quiet_hours_title)
        composeRule.onNodeWithText(titel, substring = true).performScrollTo().performClick()

        composeRule.onNodeWithTag("slider_meter_quiet_hours_threshold").performScrollTo().assertIsDisplayed()
    }
}
