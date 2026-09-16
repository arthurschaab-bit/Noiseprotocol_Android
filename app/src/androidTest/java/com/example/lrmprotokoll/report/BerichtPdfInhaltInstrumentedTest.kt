package com.example.lrmprotokoll.report

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.data.MeasurementEntity
import com.example.lrmprotokoll.data.NoiseRecord
import com.example.lrmprotokoll.data.SessionEntity
import com.example.lrmprotokoll.report.pdf.Seitenlauf
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checkliste Button/Screen-Coverage Phase 5: [BerichtScreenAndroidTest] (app/src/androidTest/.../ui)
 * prueft bislang nur, dass der Zeitraumbericht-Dialog UI-seitig oeffnet/schliesst - nie, dass ein
 * Klick auf einen Preset tatsaechlich eine echte, inhaltlich plausible PDF-Datei erzeugt.
 *
 * [GesamtberichtExportTest]s eigenes KDoc dokumentiert, warum das nicht unter Robolectric geht:
 * `PdfDocument.startPage()` wirft dort zuverlaessig eine `IllegalStateException` - das eigentliche
 * Zeichnen ist nur auf echter Android-Grafik-Hardware pruefbar. Deshalb hier auf dem echten
 * Emulator, direkt gegen die Export-Klassen (nicht ueber die UI - das Oeffnen/Schliessen des
 * Dialogs selbst ist bereits in [BerichtScreenAndroidTest] abgedeckt, eine zusaetzliche
 * Klick-Kette bis zum Share-Intent haette hier nur Testinfrastruktur verdoppelt, ohne mehr echten
 * Inhalt zu pruefen).
 */
@RunWith(AndroidJUnit4::class)
class BerichtPdfInhaltInstrumentedTest {

    private lateinit var app: LaermprotokollApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.container.database.clearAllTables()
    }

    @After
    fun tearDown() {
        app.container.database.clearAllTables()
    }

    private fun pruefePdfKopf(datei: File) {
        assertTrue("PDF-Datei muss angelegt worden sein", datei.exists())
        assertTrue("PDF-Datei darf nicht leer sein", datei.length() > 0)
        val kopf = ByteArray(5)
        datei.inputStream().use { it.read(kopf) }
        assertEquals("%PDF-", String(kopf, Charsets.US_ASCII))
    }

    @Test
    fun periodenberichtPdfEnthaeltEineEchteSeiteMitDerErwartetenGroesse() = runBlocking {
        val db = app.container.database
        val basis = 3_500_000_000_000L
        val sessionId = db.sessionDao().insert(
            SessionEntity(
                startedAt = basis, endedAt = basis + 10_000,
                deviceAddress = "AA:BB:CC", deviceName = "PCE-323", weighting = "A", timeWeighting = "F",
            )
        )
        db.measurementDao().insertAll(
            listOf(
                MeasurementEntity(sessionId = sessionId, timestamp = basis + 1_000, levelDb = 55.0, weighting = "A", flags = 0),
                MeasurementEntity(sessionId = sessionId, timestamp = basis + 5_000, levelDb = 62.0, weighting = "A", flags = 0),
            )
        )
        db.noiseDao().insert(
            NoiseRecord(timestamp = basis + 2_000, amplitude = 0.0, dbValue = 58.0, filePath = "", label = "Testereignis")
        )

        val bericht = ermittlePeriodenBericht(db, basis, basis + 10_000)
        val datei = PeriodenBerichtExport(app).exportierePdf(bericht, "Test-Zeitraumbericht")

        try {
            pruefePdfKopf(datei)
            ParcelFileDescriptor.open(datei, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    assertEquals(
                        "Ein Zeitraumbericht ohne Tagesseiten hat genau eine Seite",
                        1,
                        renderer.pageCount,
                    )
                    renderer.openPage(0).use { seite ->
                        assertEquals(Seitenlauf.SEITE_BREITE.toInt(), seite.width)
                        assertEquals(Seitenlauf.SEITE_HOEHE.toInt(), seite.height)
                    }
                }
            }
        } finally {
            datei.delete()
        }
    }

    @Test
    fun gesamtberichtPdfHatDeckblattUndMindestensEineTagesseite() = runBlocking {
        val db = app.container.database
        val basis = 3_600_000_000_000L
        val zweiTage = 2 * 24 * 60 * 60 * 1000L
        val sessionId = db.sessionDao().insert(
            SessionEntity(
                startedAt = basis, endedAt = basis + zweiTage,
                deviceAddress = "AA:BB:CC", deviceName = "PCE-323", weighting = "A", timeWeighting = "F",
            )
        )
        db.measurementDao().insertAll(
            listOf(
                MeasurementEntity(sessionId = sessionId, timestamp = basis + 1_000, levelDb = 50.0, weighting = "A", flags = 0),
                MeasurementEntity(sessionId = sessionId, timestamp = basis + zweiTage - 1_000, levelDb = 65.0, weighting = "A", flags = 0),
            )
        )

        val bericht = ermittleGesamtbericht(db, basis, basis + zweiTage)
        val stammdaten = GesamtberichtStammdaten.leer()
        val datei = GesamtberichtExport(app).exportierePdf(bericht, stammdaten, "Test-Gesamtbericht")

        try {
            pruefePdfKopf(datei)
            ParcelFileDescriptor.open(datei, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    // Deckblatt + mindestens eine Tagesseite - die genaue Anzahl haengt von der
                    // Kalendertag-Aufteilung des (willkuerlichen) Zeitfensters ab, siehe
                    // ermittleGesamtbericht(), deshalb keine exakte Seitenzahl.
                    assertTrue(
                        "Erwartet Deckblatt + mind. 1 Tagesseite, war ${renderer.pageCount}",
                        renderer.pageCount >= 2,
                    )
                }
            }
        } finally {
            datei.delete()
        }
    }
}
