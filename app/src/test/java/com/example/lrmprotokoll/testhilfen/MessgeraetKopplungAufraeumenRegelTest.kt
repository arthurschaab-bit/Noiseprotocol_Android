package com.example.lrmprotokoll.testhilfen

import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Gegenprobe zu [MessgeraetKopplungAufraeumenRegel]. Der Nachweis muss hier stehen und nicht in
 * einer der acht angefassten Klassen: ein Test, der prueft, dass die Kopplung am Klassenanfang
 * leer ist, haengt an der Ausfuehrungsreihenfolge und waere damit selbst ein Flake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MessgeraetKopplungAufraeumenRegelTest {
    private val app: LaermprotokollApp get() = ApplicationProvider.getApplicationContext()

    private val beschreibung: Description =
        Description.createTestDescription("EineKlasse", "einTest")

    @Test
    fun einGepinntesGeraetIstNachDemTestWiederWeg() {
        val statement =
            MessgeraetKopplungAufraeumenRegel().apply(
                object : Statement() {
                    override fun evaluate() {
                        app.container.settingsManager.meterDeviceAddress = "20:3C:AE:11:22:33"
                        app.container.settingsManager.meterDeviceName = "PCE-323"
                        // Im Test selbst ist die Kopplung noch da - die Regel raeumt erst danach.
                        assertEquals(
                            "20:3C:AE:11:22:33",
                            app.container.settingsManager.meterDeviceAddress,
                        )
                    }
                },
                beschreibung,
            )

        statement.evaluate()

        assertNull(app.container.settingsManager.meterDeviceAddress)
        assertNull(app.container.settingsManager.meterDeviceName)
    }

    @Test
    fun auchNachEinemFehlschlagWirdAufgeraeumtUndDerFehlerBleibtSichtbar() {
        val statement =
            MessgeraetKopplungAufraeumenRegel().apply(
                object : Statement() {
                    override fun evaluate() {
                        app.container.settingsManager.meterDeviceAddress = "20:3C:AE:11:22:33"
                        throw IllegalStateException("der eigentliche Fehler")
                    }
                },
                beschreibung,
            )

        val geworfen = runCatching { statement.evaluate() }.exceptionOrNull()

        assertTrue("Die Regel darf den Fehler nicht verschlucken", geworfen is IllegalStateException)
        assertNull("Gerade im Fehlerfall muss aufgeraeumt werden", app.container.settingsManager.meterDeviceAddress)
    }
}
