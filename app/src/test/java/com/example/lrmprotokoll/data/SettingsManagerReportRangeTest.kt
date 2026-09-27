package com.example.lrmprotokoll.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsManagerReportRangeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun entferneTestwerte() {
        context.getSharedPreferences("noise_settings", Context.MODE_PRIVATE)
            .edit()
            .remove("high_end_bericht_erster_tag")
            .remove("high_end_bericht_letzter_tag")
            .commit()
    }

    @Test
    fun merktDenZeitraumUeberEineNeueInstanz() {
        assertNull(SettingsManager(context).letzterHighEndBerichtszeitraum())

        SettingsManager(context).speichereHighEndBerichtszeitraum(20_000L, 20_006L)

        assertEquals(20_000L to 20_006L, SettingsManager(context).letzterHighEndBerichtszeitraum())
    }

    @Test
    fun ignoriertUnvollstaendigGespeichertenZeitraum() {
        context.getSharedPreferences("noise_settings", Context.MODE_PRIVATE)
            .edit()
            .putLong("high_end_bericht_erster_tag", 20_000L)
            .commit()

        assertNull(SettingsManager(context).letzterHighEndBerichtszeitraum())
    }
}
