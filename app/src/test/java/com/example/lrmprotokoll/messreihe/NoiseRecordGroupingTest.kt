package com.example.lrmprotokoll.messreihe

import com.example.lrmprotokoll.data.NoiseRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testluecken-Auftrag Stufe 6 / MainActivity-Refactor: [gruppiereNachTag] und
 * [unklassifizierteAufnahmen] waren vorher inline in `NoiseProtocolApp()` (MainActivity.kt) und
 * damit ohne Compose-Test nicht pruefbar. Reine Funktionen, hier direkt getestet.
 */
class NoiseRecordGroupingTest {

    private fun sampleRecord(
        id: Long,
        timestamp: Long,
        filePath: String = "/path/$id.wav",
        label: String? = null,
        detectedLabel: String? = null,
    ) = NoiseRecord(
        id = id,
        timestamp = timestamp,
        amplitude = 1000.0,
        dbValue = 50.0,
        filePath = filePath,
        label = label,
        detectedLabel = detectedLabel,
    )

    // Immer mit derselben Formatierung wie die Produktion arbeiten statt Datumsstrings fest zu
    // verdrahten - so ist der Test unabhaengig von der Zeitzone des CI-Runners.
    private val locale = Locale.GERMANY
    private fun tagVon(timestamp: Long): String = SimpleDateFormat("dd.MM.yyyy", locale).format(Date(timestamp))

    @Test
    fun leereListeErgibtLeereGruppierung() {
        assertTrue(gruppiereNachTag(emptyList(), locale).isEmpty())
    }

    @Test
    fun einzelneAufnahmeErgibtEineGruppeMitEinemEintrag() {
        val jetzt = System.currentTimeMillis()
        val record = sampleRecord(1, jetzt)

        val gruppen = gruppiereNachTag(listOf(record), locale)

        assertEquals(1, gruppen.size)
        assertEquals(listOf(record), gruppen[tagVon(jetzt)])
    }

    @Test
    fun mehrereAufnahmenAmSelbenTagWerdenZusammengefasst() {
        val tagesanfang = System.currentTimeMillis()
        val a = sampleRecord(1, tagesanfang)
        val b = sampleRecord(2, tagesanfang + TimeUnit.MINUTES.toMillis(5))

        val gruppen = gruppiereNachTag(listOf(a, b), locale)

        assertEquals(1, gruppen.size)
        assertEquals(listOf(a, b), gruppen[tagVon(tagesanfang)])
    }

    @Test
    fun aufnahmenAnVerschiedenenTagenErgebenGetrennteGruppenInAntreffreihenfolge() {
        val tag1 = System.currentTimeMillis()
        val tag2 = tag1 + TimeUnit.DAYS.toMillis(3)
        val a = sampleRecord(1, tag1)
        val b = sampleRecord(2, tag2)

        val gruppen = gruppiereNachTag(listOf(a, b), locale)

        assertEquals(
            "Reihenfolge der Gruppen muss der Antreffreihenfolge in der Eingabeliste folgen " +
                "(i.d.R. absteigend nach Zeit aus dem DAO), nicht alphabetisch sortiert sein",
            listOf(tagVon(tag1), tagVon(tag2)),
            gruppen.keys.toList(),
        )
        assertEquals(listOf(a), gruppen[tagVon(tag1)])
        assertEquals(listOf(b), gruppen[tagVon(tag2)])
    }

    @Test
    fun unklassifizierteAufnahmenFiltertJedenAusschlussgrundEinzelnKorrekt() {
        val ok = sampleRecord(1, 1L)
        val mitKiLabel = sampleRecord(2, 2L, detectedLabel = "Hund")
        val mitManuellemLabel = sampleRecord(3, 3L, label = "Baustelle")
        val ohneAudio = sampleRecord(4, 4L, filePath = "")
        val ohneTreffer = sampleRecord(5, 5L, detectedLabel = NICHT_ERKANNT_MARKER)

        val ergebnis = unklassifizierteAufnahmen(listOf(ok, mitKiLabel, mitManuellemLabel, ohneAudio, ohneTreffer))

        assertEquals(listOf(ok), ergebnis)
    }

    @Test
    fun unklassifizierteAufnahmenMitLeererListeErgibtLeereListe() {
        assertTrue(unklassifizierteAufnahmen(emptyList()).isEmpty())
    }

    @Test
    fun klassifizierungsStatusUnterscheidetAlleZustaendeUndEintraegeOhneAudio() {
        val offen = sampleRecord(1, 1L)
        assertEquals(KlassifizierungsStatus.AUSSTEHEND, klassifizierungsStatus(offen, "BATCH"))
        assertEquals(KlassifizierungsStatus.DEAKTIVIERT, klassifizierungsStatus(offen, "OFF"))
        assertEquals(
            KlassifizierungsStatus.NICHT_ERKANNT,
            klassifizierungsStatus(offen.copy(detectedLabel = NICHT_ERKANNT_MARKER), "BATCH"),
        )
        assertEquals(
            KlassifizierungsStatus.NICHT_ERKANNT,
            klassifizierungsStatus(offen.copy(detectedLabel = "Not recognized"), "BATCH"),
        )
        assertEquals(
            KlassifizierungsStatus.ERKANNT,
            klassifizierungsStatus(offen.copy(detectedLabel = "Bohren"), "OFF"),
        )
        assertEquals(null, klassifizierungsStatus(offen.copy(filePath = ""), "BATCH"))
    }

    /**
     * [erkanntesLabel] ist die Gegenprobe zu [klassifizierungsStatus] fuer alle Stellen, die nur
     * ein verwertbares Label wollen (Bereinigung, Berichte, Drive-Sync, Ereignistitel). Ohne sie
     * bedeutet `detectedLabel != null` seit F-17 nicht mehr "erkannt".
     */
    @Test
    fun erkanntesLabelLiefertNurEchteTreffer() {
        assertEquals("Bohren", erkanntesLabel("Bohren"))
        assertEquals(null, erkanntesLabel(null))
        assertEquals(null, erkanntesLabel(""))
        assertEquals(null, erkanntesLabel("   "))
        assertEquals(null, erkanntesLabel(NICHT_ERKANNT_MARKER))
        assertEquals(null, erkanntesLabel("Not recognized"))
    }

    /**
     * Der gespeicherte Wert ist seit dem 29.09.2026 sprachunabhaengig. Die beiden alten
     * Oberflaechenzeichenketten bleiben in der Menge, weil Bestandsdatensaetze sie tragen und
     * **nicht** migriert werden. Faellt dieser Test, wurde eine davon entfernt — dann rutschen
     * genau diese Altdatensaetze still in den Zweig "erkannt".
     */
    @Test
    fun derMarkerIstSprachunabhaengigUndAltwerteBleibenGueltig() {
        assertEquals("__nicht_erkannt__", NICHT_ERKANNT_MARKER)
        assertEquals(setOf(NICHT_ERKANNT_MARKER, "Nicht erkannt", "Not recognized"), NICHT_ERKANNT_LABELS)
        assertTrue(istNichtErkannt(NICHT_ERKANNT_MARKER))
        assertTrue(istNichtErkannt("Nicht erkannt"))
        assertTrue(istNichtErkannt("Not recognized"))
        assertFalse(istNichtErkannt("Bohren"))
        assertFalse(istNichtErkannt(null))
    }

    /**
     * Der Marker ist nicht lesbar; ohne diese Bruecke faende die Textsuche nach "nicht erkannt"
     * seit der Umstellung nichts mehr.
     */
    @Test
    fun dieSucheFindetNichtErkannteAufnahmenWeiterhin() {
        assertTrue(nichtErkanntPasstZurSuche(NICHT_ERKANNT_MARKER, "nicht erkannt"))
        assertTrue(nichtErkanntPasstZurSuche(NICHT_ERKANNT_MARKER, "erkannt"))
        assertTrue(nichtErkanntPasstZurSuche(NICHT_ERKANNT_MARKER, "recognized"))
        assertTrue(nichtErkanntPasstZurSuche("Nicht erkannt", "nicht"))
        assertFalse(nichtErkanntPasstZurSuche(NICHT_ERKANNT_MARKER, "bohren"))
        assertFalse(nichtErkanntPasstZurSuche("Bohren", "bohren"))
        assertFalse(nichtErkanntPasstZurSuche(NICHT_ERKANNT_MARKER, "   "))
    }
}
