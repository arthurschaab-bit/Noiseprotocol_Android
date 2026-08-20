package com.example.lrmprotokoll.report

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.example.lrmprotokoll.data.MeasurementEntity
import com.example.lrmprotokoll.data.MinuteAggregateEntity
import com.example.lrmprotokoll.data.SessionEntity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Prueft nur den CSV-Pfad - dass die Datei tatsaechlich geschrieben wird und plausiblen Inhalt
 * hat (der exakte CSV-Inhalt ist bereits in MessreiheCsvTest gegen die Formatierung geprueft,
 * hier geht es um das Datei-I/O selbst).
 *
 * KEIN PDF-Test hier: `PdfDocument()` wirft unter Robolectric bei jedem `startPage()`-Aufruf
 * `IllegalStateException: document is closed!`, unabhaengig vom Testinhalt und reproduzierbar
 * auch bei isolierter Ausfuehrung eines einzelnen Tests - ein Robolectric-Limit bei der
 * PdfDocument-Shadow, keine Aussage ueber die Korrektheit von [MessreiheExport.exportierePdf].
 * Verifiziert nur durch Kompilieren (`assembleDebug`) und muss am echten Geraet geprueft werden
 * (siehe docs/PROMPT_M7.md) - dieselbe Kategorie Luecke wie EncryptedSharedPreferences in M6.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MessreiheExportTest {

    private val session = SessionEntity(
        id = 1, startedAt = 1755324000000, endedAt = 1755327600000,
        deviceAddress = "AA:BB:CC:DD:EE:FF", deviceName = "PCE-323",
        weighting = null, timeWeighting = null,
    )

    private fun export() = MessreiheExport(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun csvAusMesswertenWirdGeschriebenUndIstNichtLeer() {
        val messwerte = listOf(
            MeasurementEntity(sessionId = 1, timestamp = 1755324000000, levelDb = 55.5, weighting = null, flags = 0),
        )
        val datei = export().exportiereCsv(session, messwerte, emptyList())
        assertTrue(datei.exists())
        assertTrue(datei.readText().contains("55,5"))
    }

    @Test
    fun csvFaelltAufAggregateZurueckWennKeineMesswerteMehrDaSind() {
        val aggregate = listOf(
            MinuteAggregateEntity(
                sessionId = 1, minuteStart = 1755324000000, leqDb = 60.0, maxDb = 65.0, minDb = 55.0,
                sampleCount = 100, weighting = null,
            )
        )
        val datei = export().exportiereCsv(session, emptyList(), aggregate)
        assertTrue(datei.readText().contains("LAeq_dB"))
        assertTrue(datei.readText().contains("60,0"))
    }
}
