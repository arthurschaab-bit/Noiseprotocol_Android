package com.example.lrmprotokoll.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsManagerMetadataPromptTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun entferneTestwert() {
        context.getSharedPreferences("noise_settings", Context.MODE_PRIVATE).edit()
            .remove("stammdaten_abfrage_letzter_tag").commit()
    }

    @Test
    fun abschlussBleibtNurFuerDiesenMessvorgangUndMesstagGespeichert() {
        val settings = SettingsManager(context)
        settings.stammdatenAbfrageFuerTagAbschliessen(17L, 1_000L)

        val nachNeustart = SettingsManager(context)
        assertTrue(nachNeustart.stammdatenAbfrageFuerTagAbgeschlossen(17L, 1_000L))
        assertFalse(nachNeustart.stammdatenAbfrageFuerTagAbgeschlossen(17L, 2_000L))
        assertFalse(nachNeustart.stammdatenAbfrageFuerTagAbgeschlossen(18L, 1_000L))
    }
}
