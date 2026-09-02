package com.example.lrmprotokoll.report

import com.example.lrmprotokoll.data.NoiseRecord
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uebernimmt die inhaltlichen Zusagen des Tagesberichts von [ReportManagerTest] und
 * [ReportManagerEinheitTest], die vorher gegen den fertigen `.txt`-Text geprueft haben.
 *
 * Mit der Umstellung auf PDF waeren diese Pruefungen sonst ersatzlos entfallen: `PdfDocument()`
 * wirft unter Robolectric bei jedem `startPage()` (siehe KDoc von [MessreiheExportTest]), und
 * aus einem PDF laesst sich der Text nicht zurueckholen. Gegen [ermittleTagesbericht] geprueft
 * bleiben sie erhalten - und brauchen jetzt nicht einmal mehr Robolectric, weil die Funktion
 * rein ist.
 *
 * Die wichtigste Zusage ist die erste: "dBA" darf nur dort stehen, wo die Frequenzbewertung fuer
 * GENAU dieses Ereignis bestaetigt war. Alles andere waere eine Genauigkeit, die es nicht gibt.
 */
class TagesberichtDatenTest {

    private val erwarteterPegel = String.format(Locale.getDefault(), "%.1f", 70.5)

    private fun ereignis(
        timestamp: Long = 1_700_000_000_000L,
        dbValue: Double = 62.0,
        calibratedDbA: Double? = null,
        meterWeighting: String? = null,
        isQuietHour: Boolean = false,
        label: String? = null,
        detectedLabel: String? = null,
    ) = NoiseRecord(
        timestamp = timestamp, amplitude = 0.0, dbValue = dbValue, filePath = "/data/rec.wav",
        calibratedDbA = calibratedDbA, meterWeighting = meterWeighting, isQuietHour = isQuietHour,
        label = label, detectedLabel = detectedLabel,
    )

    // --- Die Einheiten-Zusage (vorher ReportManagerEinheitTest) ---

    @Test
    fun bestaetigteBewertungWirdAlsDbAAusgewiesen() {
        val daten = ermittleTagesbericht(listOf(ereignis(calibratedDbA = 70.5, meterWeighting = "A")))
        assertEquals("$erwarteterPegel dBA (PCE-323)", daten.zeilen.single().pegel)
    }

    @Test
    fun unbestaetigteBewertungWirdNurAlsDbAusgewiesen() {
        // Der kalibrierte Wert liegt vor, die Bewertung war aber nicht bestaetigt - dann darf
        // kein "dBA" im Bericht stehen.
        val daten = ermittleTagesbericht(listOf(ereignis(calibratedDbA = 70.5, meterWeighting = null)))
        val zeile = daten.zeilen.single()
        assertEquals("$erwarteterPegel dB (PCE-323)", zeile.pegel)
        assertFalse("dBA darf ohne bestaetigte Bewertung nicht auftauchen", zeile.pegel.contains("dBA"))
    }

    @Test
    fun cBewertungWirdAlsDbCAusgewiesen() {
        val daten = ermittleTagesbericht(listOf(ereignis(calibratedDbA = 70.5, meterWeighting = "C")))
        assertTrue(daten.zeilen.single().pegel.contains("dBC"))
    }

    @Test
    fun reinerMikrofonwertWirdAlsSolcherGekennzeichnet() {
        val daten = ermittleTagesbericht(listOf(ereignis(calibratedDbA = null, dbValue = 62.0)))
        assertTrue(daten.zeilen.single().pegel.endsWith("dB (Mikrofon)"))
    }

    @Test
    fun geraetenameWirdUebernommen() {
        val daten = ermittleTagesbericht(listOf(ereignis(calibratedDbA = 70.5, meterWeighting = "A")), "PCE-428")
        assertEquals("PCE-428", daten.geraet)
        assertTrue(daten.zeilen.single().pegel.contains("(PCE-428)"))
    }

    // --- Der Hinweis auf unbestaetigte Bewertungen ---

    @Test
    fun hinweisErscheintNurWennEinKalibrierterWertOhneBewertungVorkommt() {
        val mitProblem = ermittleTagesbericht(listOf(ereignis(calibratedDbA = 70.5, meterWeighting = null)))
        assertNotNull("Der Hinweis gehoert in den Bericht, wenn eine Bewertung unbestaetigt war", mitProblem.bewertungsHinweis)

        val ohneProblem = ermittleTagesbericht(listOf(ereignis(calibratedDbA = 70.5, meterWeighting = "A")))
        assertNull("Ohne unbestaetigte Bewertung darf kein Hinweis erscheinen", ohneProblem.bewertungsHinweis)

        val nurMikrofon = ermittleTagesbericht(listOf(ereignis(calibratedDbA = null)))
        assertNull("Ein reiner Mikrofonwert ist kein Fall fuer den Hinweis", nurMikrofon.bewertungsHinweis)
    }

    // --- Kennzahlen (vorher ReportManagerTest gegen den Berichtstext) ---

    @Test
    fun kennzahlenWerdenKorrektErmittelt() {
        val daten = ermittleTagesbericht(
            listOf(
                ereignis(dbValue = 42.0),
                ereignis(dbValue = 55.0, isQuietHour = true),
                ereignis(calibratedDbA = 71.0, meterWeighting = "A"),
            )
        )
        assertEquals(3, daten.anzahlEreignisse)
        assertEquals(1, daten.anzahlRuhezeit)
        // Spitzenpegel bevorzugt den kalibrierten Wert, wo es einen gibt.
        assertEquals(71.0, daten.spitzenpegel, 0.01)
    }

    @Test
    fun leererTagLiefertNullwerteStattEinerAusnahme() {
        val daten = ermittleTagesbericht(emptyList())
        assertEquals(0, daten.anzahlEreignisse)
        assertEquals(0.0, daten.spitzenpegel, 0.01)
        assertTrue(daten.zeilen.isEmpty())
        assertNull(daten.bewertungsHinweis)
        // Ohne Ereignisse gibt es keinen Zeitstempel - das Datum faellt auf "heute" zurueck und
        // muss trotzdem ein plausibles Format haben, statt leer zu bleiben.
        assertTrue(daten.datum.matches(Regex("""\d{2}\.\d{2}\.\d{4}""")))
    }

    @Test
    fun ruhezeitUndLabelsLandenInDerZeile() {
        val daten = ermittleTagesbericht(
            listOf(ereignis(isQuietHour = true, label = "Presslufthammer", detectedLabel = "Jackhammer"))
        )
        val zeile = daten.zeilen.single()
        assertTrue(zeile.inRuhezeit)
        assertEquals("Presslufthammer", zeile.label)
        assertEquals("Jackhammer", zeile.kiLabel)
    }
}
