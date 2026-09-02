package com.example.lrmprotokoll.audio

import com.example.lrmprotokoll.data.KlassifikationsRohdaten
import com.example.lrmprotokoll.data.ReferenceSound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * KI-Umbau Etappe 1.5/2.5: Regressionstest fuer [leiteLabelAb] - reine JVM-Tests ohne
 * Robolectric, kein Geraet noetig (Arbeitsweise-Regel 3).
 *
 * Etappe 2.5 hat den Rueckgabetyp von `Befund(label: String)` auf [BaulaermBefund] umgestellt.
 * Die Etappe-1-Tests fuer den frueheren "Top-1-Label ueber Einheitsschwelle"-Pfad sind ENTFALLEN,
 * weil genau diese Entscheidungslogik durch Etappe 2 (Gruppen-Score + Zeitaggregation) ersetzt
 * wurde, nicht mehr existiert - deren Testabdeckung uebernehmen jetzt GruppenScoreTest,
 * ZeitaggregationTest und BaulaermBefundTest. Was UNVERAENDERT aus Etappe 1 uebernommen ist und
 * hier weiter getestet wird: der Referenzmuster-Abgleich hat weiterhin Vorrang vor dem
 * Gruppen-Score-Pfad (Auftrag Abschnitt 3.3, siehe [BaulaermBefund.gelernteQuelle]-KDoc).
 */
class LabelAbleitungTest {

    private fun rohdatenMit(topKlassen: List<NoiseClassifier.ScoredCategory>) = KlassifikationsRohdaten(
        recordId = 1,
        modellVersion = "yamnet.tflite@test",
        klassifiziertAm = 1_700_000_000_000L,
        frameAnzahl = topKlassen.size,
        frameDauerMs = 960,
        frameHopMs = 480,
        klassenIndizes = ROHDATEN_KLASSEN_INDIZES,
        frameScores = ByteArray(0),
        topKlassen = kodiereTopKlassen(topKlassen),
    )

    private val testLabelMapping = mapOf("Hammer" to "Hämmern", "Drill" to "Bohren")
    private val konfigurationOhneReferenzen = AbleitungsKonfiguration(
        referenzMuster = emptyList(), labelMapping = testLabelMapping,
    )

    @Test
    fun ohneReferenztrefferBleibtGelernteQuelleUnbelegt() {
        val rohdaten = rohdatenMit(listOf(NoiseClassifier.ScoredCategory("Hammer", 0.42f)))

        val befund = leiteLabelAb(rohdaten, konfigurationOhneReferenzen)

        assertNull(befund.gelernteQuelle)
    }

    @Test
    fun referenzmusterMitUeberwiegenderUeberschneidungSchlaegtDasStandardLabel() {
        val referenz = ReferenceSound(id = 1, name = "Baustelle Hofseite", pattern = "Hammer,Drill,Tools")
        val rohdaten = rohdatenMit(
            listOf(
                NoiseClassifier.ScoredCategory("Hammer", 0.42f),
                NoiseClassifier.ScoredCategory("Drill", 0.30f),
                NoiseClassifier.ScoredCategory("Tools", 0.20f),
            ),
        )
        val konfiguration = AbleitungsKonfiguration(referenzMuster = listOf(referenz), labelMapping = testLabelMapping)

        val befund = leiteLabelAb(rohdaten, konfiguration)

        assertEquals("Baustelle Hofseite (100%)", befund.gelernteQuelle)
        assertEquals("Gelernt: Baustelle Hofseite (100%)", formatiereBaulaermBefund(befund, testLabelMapping))
    }

    @Test
    fun referenzmusterMitGenauHalberUeberschneidungGreiftNicht() {
        // Original-Regel ist ">50%", nicht ">=50%" - eine exakte Haelfte darf nicht triggern.
        val referenz = ReferenceSound(id = 1, name = "Test", pattern = "Hammer,Drill,Tools,Truck")
        val rohdaten = rohdatenMit(
            listOf(NoiseClassifier.ScoredCategory("Hammer", 0.42f), NoiseClassifier.ScoredCategory("Drill", 0.30f)),
        )
        val konfiguration = AbleitungsKonfiguration(referenzMuster = listOf(referenz), labelMapping = testLabelMapping)

        val befund = leiteLabelAb(rohdaten, konfiguration)

        assertNull(
            "Genau 2 von 4 Musterklassen = 50% - die Original-Regel verlangt STRIKT ueber 50%",
            befund.gelernteQuelle,
        )
    }

    @Test
    fun ersteReferenzMitTrefferGewinntVorSpaeteren() {
        val ersteReferenz = ReferenceSound(id = 1, name = "Erste", pattern = "Hammer,Drill")
        val zweiteReferenz = ReferenceSound(id = 2, name = "Zweite", pattern = "Hammer,Drill")
        val rohdaten = rohdatenMit(
            listOf(NoiseClassifier.ScoredCategory("Hammer", 0.42f), NoiseClassifier.ScoredCategory("Drill", 0.30f)),
        )
        val konfiguration = AbleitungsKonfiguration(
            referenzMuster = listOf(ersteReferenz, zweiteReferenz), labelMapping = testLabelMapping,
        )

        val befund = leiteLabelAb(rohdaten, konfiguration)

        assertEquals("Erste (100%)", befund.gelernteQuelle)
    }

    @Test
    fun keineAuswertbarenFramesErgibtUnklarStattAbsturz() {
        val rohdaten = rohdatenMit(emptyList())

        val befund = leiteLabelAb(rohdaten, konfigurationOhneReferenzen)

        assertEquals(Einstufung.UNKLAR, befund.einstufung)
        assertNull(befund.gelernteQuelle)
    }

    @Test
    fun topKlassenKodierungUeberstehtHinUndRueckwegUnveraendert() {
        val original = listOf(
            NoiseClassifier.ScoredCategory("Hammer", 0.4231f),
            NoiseClassifier.ScoredCategory("Jackhammer", 0.1099f),
        )

        val dekodiert = dekodiereTopKlassen(kodiereTopKlassen(original))

        assertEquals(original, dekodiert)
    }
}
