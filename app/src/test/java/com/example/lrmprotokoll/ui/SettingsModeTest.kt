package com.example.lrmprotokoll.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.data.SettingsManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsModeTest {

    private lateinit var context: Context
    private lateinit var settings: SettingsManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("noise_settings", Context.MODE_PRIVATE).edit().clear().commit()
        settings = SettingsManager(context)
    }

    @Test
    fun diagnoseLoggingIstStandardmaessigAktiv() {
        // Requirement 3: Diagnose-log soll standardmäßig aktiv sein
        assertTrue(settings.diagnoseLoggingAktiv)
    }

    @Test
    fun proModusKannUmgerechnetUndPersistiertWerden() {
        // Default ist Lite Mode (isProMode == false)
        assertFalse(settings.isProMode)

        settings.isProMode = true
        assertTrue(settings.isProMode)

        val freshSettings = SettingsManager(context)
        assertTrue(freshSettings.isProMode)

        freshSettings.isProMode = false
        assertFalse(freshSettings.isProMode)
    }
}
