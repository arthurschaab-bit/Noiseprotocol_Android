package com.example.lrmprotokoll.audio

import com.example.lrmprotokoll.data.KlassifikationsRohdaten
import com.example.lrmprotokoll.data.ReferenceSound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * KI-Umbau Etappe 1.5: Regressionstest fuer [leiteLabelAb] - reine JVM-Tests ohne Robolectric,
 * kein Geraet noetig (Arbeitsweise-Regel 3).
 *
 * Kein Vergleich gegen eine echte Laufzeit-Inferenz (kein Geraet/Modell in dieser Umgebung
 * verfuegbar, siehe Abschlussbericht) - stattdessen transkribiert jeder Testfall exakt die vor
 * der Extraktion in [NoiseClassifier.classify] dokumentierte Regel:
 * 1. Referenzmuster-Ueberlappung >50% -> "Gelernt: <Name> (X%)".
 * 2. Sonst staerkstes Label ausserhalb der Ausschlussliste, eingedeutscht, mit Prozentwert.
 * 3. Kein Kandidat -> null.
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

    private val labelMapping = mapOf("Hammer" to "Hämmern", "Drill" to "Bohren")
    private val konfigurationOhneReferenzen = AbleitungsKonfiguration(
        referenzMuster = emptyList(), labelMapping = labelMapping,
    )

    @Test
    fun topLabelUeberSchwelleWirdEingedeutschtMitProzentwert() {
        val rohdaten = rohdatenMit(
            listOf(NoiseClassifier.ScoredCategory("Hammer", 0.42f), NoiseClassifier.ScoredCategory("Speech", 0.10f)),
        )

        val befund = leiteLabelAb(rohdaten, konfigurationOhneReferenzen)

        assertEquals(Befund("Hämmern (42%)"), befund)
    }

    @Test
    fun unbekannteKlasseOhneMappingBleibtImOriginalnamen() {
        val rohdaten = rohdatenMit(listOf(NoiseClassifier.ScoredCategory("Jackhammer", 0.31f)))

        val befund = leiteLabelAb(rohdaten, konfigurationOhneReferenzen)

        assertEquals(Befund("Jackhammer (31%)"), befund)
    }

    @Test
    fun ausschlussLabelsWerdenUebersprungenAuchAlsStaerkstesLabel() {
        val rohdaten = rohdatenMit(
            listOf(
                NoiseClassifier.ScoredCategory("Silence", 0.90f),
                NoiseClassifier.ScoredCategory("Background noise", 0.50f),
                NoiseClassifier.ScoredCategory("Drill", 0.25f),
            ),
        )

        val befund = leiteLabelAb(rohdaten, konfigurationOhneReferenzen)

        assertEquals(
            "Silence/Background noise haben die hoechsten Scores, muessen aber uebersprungen " +
                "werden - das erste zulaessige Label ist Drill",
            Befund("Bohren (25%)"), befund,
        )
    }

    @Test
    fun ausschliesslichAusschlussLabelsErgibtNull() {
        val rohdaten = rohdatenMit(
            listOf(NoiseClassifier.ScoredCategory("Silence", 0.90f), NoiseClassifier.ScoredCategory("Noise", 0.40f)),
        )

        assertNull(leiteLabelAb(rohdaten, konfigurationOhneReferenzen))
    }

    @Test
    fun leereKandidatenlisteErgibtNull() {
        assertNull(leiteLabelAb(rohdatenMit(emptyList()), konfigurationOhneReferenzen))
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
        val konfiguration = AbleitungsKonfiguration(referenzMuster = listOf(referenz), labelMapping = labelMapping)

        val befund = leiteLabelAb(rohdaten, konfiguration)

        assertEquals(Befund("Gelernt: Baustelle Hofseite (100%)"), befund)
    }

    @Test
    fun referenzmusterMitGenauHalberUeberschneidungGreiftNicht() {
        // Original-Regel ist ">50%", nicht ">=50%" - eine exakte Haelfte darf nicht triggern.
        val referenz = ReferenceSound(id = 1, name = "Test", pattern = "Hammer,Drill,Tools,Truck")
        val rohdaten = rohdatenMit(
            listOf(NoiseClassifier.ScoredCategory("Hammer", 0.42f), NoiseClassifier.ScoredCategory("Drill", 0.30f)),
        )
        val konfiguration = AbleitungsKonfiguration(referenzMuster = listOf(referenz), labelMapping = labelMapping)

        val befund = leiteLabelAb(rohdaten, konfiguration)

        assertEquals(
            "Genau 2 von 4 Musterklassen = 50% - die Original-Regel verlangt STRIKT ueber 50%",
            Befund("Hämmern (42%)"), befund,
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
            referenzMuster = listOf(ersteReferenz, zweiteReferenz), labelMapping = labelMapping,
        )

        val befund = leiteLabelAb(rohdaten, konfiguration)

        assertEquals(Befund("Gelernt: Erste (100%)"), befund)
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
