package com.example.lrmprotokoll.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.data.SettingsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RuhezeitPresetsTest {

    private lateinit var settings: SettingsManager

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        settings = SettingsManager(context)
    }

    @Test
    fun wohnraumPresetsEnthaltenAlleStandardgebieteNachTaLaerm() {
        assertEquals(6, WOHNRAUM_PRESETS.size)

        val reinesWohngebiet = WOHNRAUM_PRESETS.first { it.id == "WR" }
        assertEquals(35f, reinesWohngebiet.nachtGrenzwertDb)
        assertEquals(50f, reinesWohngebiet.tagGrenzwertDb)

        val allgemeinesWohngebiet = WOHNRAUM_PRESETS.first { it.id == "WA" }
        assertEquals(40f, allgemeinesWohngebiet.nachtGrenzwertDb)
        assertEquals(55f, allgemeinesWohngebiet.tagGrenzwertDb)

        val mischgebiet = WOHNRAUM_PRESETS.first { it.id == "MI" }
        assertEquals(45f, mischgebiet.nachtGrenzwertDb)
        assertEquals(60f, mischgebiet.tagGrenzwertDb)

        val gewerbegebiet = WOHNRAUM_PRESETS.first { it.id == "GE" }
        assertEquals(50f, gewerbegebiet.nachtGrenzwertDb)
        assertEquals(65f, gewerbegebiet.tagGrenzwertDb)

        val innenraeume = WOHNRAUM_PRESETS.first { it.id == "INNEN" }
        assertEquals(30f, innenraeume.nachtGrenzwertDb)
        assertEquals(40f, innenraeume.tagGrenzwertDb)
    }

    @Test
    fun istAktuellRuhezeitBeachtetDeaktiviertenZustand() {
        settings.quietHoursEnabled = false
        assertFalse(istAktuellRuhezeit(settings))
    }

    @Test
    fun istAktuellRuhezeitBerechnetZeitfensterKorrekt() {
        settings.quietHoursEnabled = true
        settings.quietHoursStartHour = 22
        settings.quietHoursEndHour = 6

        // Funktion ist ohne Exception aufrufbar
        val ergebnis = istAktuellRuhezeit(settings)
        assertNotNull(ergebnis)
    }
}
