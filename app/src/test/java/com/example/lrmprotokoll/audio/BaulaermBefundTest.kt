package com.example.lrmprotokoll.audio

import com.example.lrmprotokoll.data.KlassifikationsRohdaten
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KI-Umbau Etappe 2.4/2.5: Integrationstests fuer [leiteBaulaermBefundAb] - die vier im Auftrag
 * (Akzeptanzkriterien Etappe 2) geforderten Testfaelle: durchgehender Laerm, kurze Einzelimpulse,
 * flackernder Grenzfall, komplette Stille. Reine JVM-Tests, kein Geraet noetig.
 */
class BaulaermBefundTest {

    private val konfiguration = BaulaermKonfiguration()
    private val frameHopMs = 480

    private fun rohdatenMitFrames(vararg frames: Map<Int, Int>): KlassifikationsRohdaten {
        val klassenIndizes = ROHDATEN_KLASSEN_INDIZES
        val frameScores = ByteArray(frames.size * klassenIndizes.size)
        frames.forEachIndexed { frameIndex, werte ->
            klassenIndizes.forEachIndexed { position, index ->
                frameScores[frameIndex * klassenIndizes.size + position] = (werte[index] ?: 0).toByte()
            }
        }
        return KlassifikationsRohdaten(
            recordId = 1,
            modellVersion = "test",
            klassifiziertAm = 0,
            frameAnzahl = frames.size,
            frameDauerMs = 960,
            frameHopMs = frameHopMs,
            klassenIndizes = klassenIndizes,
            frameScores = frameScores,
            topKlassen = "",
        )
    }

    private fun frame(vararg werte: Pair<Int, Int>): Map<Int, Int> = mapOf(*werte)
    private fun rate(anteil: Float): Int = (anteil * 255).toInt().coerceIn(0, 255)

    @Test
    fun durchgehenderLaermWirdAlsBaulaermMitEinemLangenBlockErkannt() {
        // 10 Frames durchgehend volle Kernklasse (Index 413 = "Hammer").
        val frames = (1..10).map { frame(413 to rate(1.0f)) }.toTypedArray()
        val rohdaten = rohdatenMitFrames(*frames)

        val befund = leiteBaulaermBefundAb(rohdaten, konfiguration)

        assertEquals(Einstufung.BAULAERM, befund.einstufung)
        assertEquals(1.0f, befund.anteil, 0.01f)
        assertEquals(1, befund.blockAnzahl)
        assertEquals(10 * frameHopMs / 1000f, befund.laengsterBlockSekunden, 0.01f)
        assertEquals("Hammer", befund.spitzenKlasse)
    }

    @Test
    fun kurzerEinzelimpulsWirdDurchDieGlaettungNichtAlsDurchgehenderBlockGewertet() {
        // Ein einzelner voller Frame zwischen 20 stillen Frames - die Median-Glaettung (Fenster 3)
        // filtert einen isolierten 1-Frame-Ausreisser komplett heraus (Median von [0, X, 0] = 0),
        // genau das ist ihr Zweck ("sonst flackert die Entscheidung frameweise"). Der ROHE
        // Gruppen-Score an dieser Stelle bleibt trotzdem hoch genug, um die Aufnahme nicht als
        // "definitiv kein Baulärm" einzustufen, sondern als "moeglich".
        val stille = List(10) { frame() }
        val impuls = frame(414 to rate(1.0f)) // Jackhammer, Impuls-Gewicht 0.3 -> roh ~0.3
        val frames = (stille + impuls + stille).toTypedArray()
        val rohdaten = rohdatenMitFrames(*frames)

        val befund = leiteBaulaermBefundAb(rohdaten, konfiguration)

        assertTrue(
            "Ein einzelner geglaetteter Frame darf keinen zusammenhaengenden Baulärm-Block ergeben - war ${befund.blockAnzahl}",
            befund.blockAnzahl == 0,
        )
        assertEquals(Einstufung.MOEGLICH, befund.einstufung)
    }

    @Test
    fun flackernderGrenzfallWirdDurchHystereseZuEinemBlockZusammengefasst() {
        // Score alterniert 0.60/0.40 um die Einstiegsschwelle (0.50) - eine NAIVE Einzelschwelle
        // (>=0.50) wuerde das in 5 Ein-Frame-Bloecke zerhacken. Median-Glaettung + Hysterese
        // (Ausstieg erst bei 0.35) fassen die Sequenz stattdessen zu einem einzigen Block zusammen.
        val hoch = frame(413 to rate(0.60f))
        val tief = frame(413 to rate(0.40f))
        val frames = arrayOf(hoch, tief, hoch, tief, hoch, tief, hoch, tief, hoch)
        val rohdaten = rohdatenMitFrames(*frames)

        val befund = leiteBaulaermBefundAb(rohdaten, konfiguration)

        assertEquals(
            "Median-Glaettung + Hysterese sollen die flackernde Sequenz zu EINEM Block zusammenfassen",
            1, befund.blockAnzahl,
        )
        assertEquals(Einstufung.BAULAERM, befund.einstufung)
    }

    // --- Etappe 3.5: Fusion (Gruppen-Score ODER Impuls-Regel) --------------------------------

    @Test
    fun impulsRegelHebtEinstufungAufBaulaermWennGruppenScoreAlleinNichtReicht() {
        // Kein Kern-/Kontext-/Impuls-Klassenscore gesetzt -> Gruppen-Score bleibt bei 0, aber
        // die physikalischen Merkmale sprechen fuer einen impulshaften, periodischen Vorgang
        // (Kurtosis ueber der Schwelle, Rate im 5-30-Hz-Fenster) mit ausreichendem relativem Pegel.
        val rohdaten = rohdatenMitFrames(frame(), frame(), frame()).copy(
            impulsKurtosis = 5f,
            impulsWiederholrateHz = 12f,
            impulsMittlererPegel = 0.1f,
        )

        val befund = leiteBaulaermBefundAb(rohdaten, konfiguration)

        assertEquals(Einstufung.BAULAERM, befund.einstufung)
        assertTrue(befund.ueberImpulsRegelErkannt)
        assertEquals(12f, befund.impulsRateHz)
        assertEquals(
            "Die Impuls-Regel darf die vom Gruppen-Score unabhaengigen Kennzahlen nicht veraendern",
            0f, befund.anteil, 0.001f,
        )
    }

    @Test
    fun impulsRegelGreiftNichtAusserhalbDesRatenbereichs() {
        // Kurtosis und Pegel passen, aber 3 Hz liegt UNTER dem Fusion-Bereich [5,30] Hz - das ist
        // der Bereich manuellen (nicht maschinellen) Haemmerns, den die Regel bewusst ausschliesst.
        val rohdaten = rohdatenMitFrames(frame(), frame(), frame()).copy(
            impulsKurtosis = 5f,
            impulsWiederholrateHz = 3f,
            impulsMittlererPegel = 0.1f,
        )

        val befund = leiteBaulaermBefundAb(rohdaten, konfiguration)

        assertEquals(Einstufung.KEIN_BAULAERM, befund.einstufung)
        assertTrue(befund.ueberImpulsRegelErkannt.not())
    }

    @Test
    fun impulsRegelGreiftNichtUnterhalbDerPegelschwelle() {
        val rohdaten = rohdatenMitFrames(frame(), frame(), frame()).copy(
            impulsKurtosis = 5f,
            impulsWiederholrateHz = 12f,
            impulsMittlererPegel = 0.01f, // unter dem Default impulsPegelSchwelleRelativ = 0.05
        )

        val befund = leiteBaulaermBefundAb(rohdaten, konfiguration)

        assertEquals(Einstufung.KEIN_BAULAERM, befund.einstufung)
    }

    @Test
    fun kalibrierterPegelHatVorrangVorDemRelativenErsatzwert() {
        // Der relative Pegel liegt UNTER seiner Schwelle, aber ein uebergebener kalibrierter
        // PCE-323-Pegel UEBER seiner - laut Auftrag hat der kalibrierte Wert Vorrang.
        val rohdaten = rohdatenMitFrames(frame(), frame(), frame()).copy(
            impulsKurtosis = 5f,
            impulsWiederholrateHz = 12f,
            impulsMittlererPegel = 0.001f,
        )

        val befund = leiteBaulaermBefundAb(rohdaten, konfiguration, kalibrierterPegelDbA = 70.0)

        assertEquals(Einstufung.BAULAERM, befund.einstufung)
        assertTrue(befund.ueberImpulsRegelErkannt)
    }

    @Test
    fun impulsRegelUeberschreibtEinenBereitsPerGruppenScoreErkanntenBaulaermBefundNicht() {
        // Durchgehender Gruppen-Score-Treffer OHNE brauchbare Impuls-Merkmale - die Einstufung
        // muss trotzdem BAULAERM bleiben (Gruppen-Score allein reicht schon), ueberImpulsRegelErkannt
        // bleibt false, weil gar keine Impuls-Pruefung noetig war.
        val frames = (1..5).map { frame(413 to rate(1.0f)) }.toTypedArray()
        val rohdaten = rohdatenMitFrames(*frames)

        val befund = leiteBaulaermBefundAb(rohdaten, konfiguration)

        assertEquals(Einstufung.BAULAERM, befund.einstufung)
        assertTrue(befund.ueberImpulsRegelErkannt.not())
    }

    @Test
    fun formatierungZeigtDenHerkunftshinweisFuerImpulsErkennung() {
        val befund = BaulaermBefund(
            anteil = 0f, laengsterBlockSekunden = 0f, blockAnzahl = 0,
            spitzenKlasse = null, spitzenScore = 0f, einstufung = Einstufung.BAULAERM,
            ueberImpulsRegelErkannt = true, impulsRateHz = 12.4f,
        )

        assertEquals(
            "Baulärm (impulsiv, 12 Hz) · 0% der Aufnahme",
            formatiereBaulaermBefund(befund, emptyMap()),
        )
    }

    @Test
    fun kompletteStilleWirdAlsKeinBaulaermEingestuftNichtAlsUnklar() {
        // "Kein Signal" ist eine positive, sichere Aussage (die Aufnahme IST leise) - anders als
        // "keine auswertbaren Frames" (UNKLAR), wo wir schlicht nichts wissen.
        val frames = (1..5).map { frame() }.toTypedArray()
        val rohdaten = rohdatenMitFrames(*frames)

        val befund = leiteBaulaermBefundAb(rohdaten, konfiguration)

        assertEquals(Einstufung.KEIN_BAULAERM, befund.einstufung)
        assertEquals(0f, befund.anteil, 0.001f)
        assertEquals(0, befund.blockAnzahl)
        assertNull(befund.spitzenKlasse)
    }

    @Test
    fun keineAuswertbarenFramesWirdAlsUnklarEingestuft() {
        val rohdaten = rohdatenMitFrames() // frameAnzahl = 0

        val befund = leiteBaulaermBefundAb(rohdaten, konfiguration)

        assertEquals(Einstufung.UNKLAR, befund.einstufung)
    }

    @Test
    fun spitzenklasseIstDieStaerksteEinzelklasseImBlockNichtDerGruppenScore() {
        val frames = arrayOf(
            frame(413 to rate(0.80f), 414 to rate(0.40f)), // Hammer 0.80, Jackhammer 0.40
            frame(414 to rate(0.95f)), // Jackhammer 0.95 - staerkster Einzelwert insgesamt
        )
        val rohdaten = rohdatenMitFrames(*frames)

        val befund = leiteBaulaermBefundAb(rohdaten, konfiguration)

        assertEquals("Jackhammer", befund.spitzenKlasse)
        assertTrue(befund.spitzenScore > 0.9f)
    }

    // --- formatiereBaulaermBefund / formatiereDauer -------------------------------------------

    private val testLabelMapping = mapOf("Hammer" to "Hämmern")

    @Test
    fun formatierungBaulaermMitBlock() {
        val befund = BaulaermBefund(
            anteil = 0.68f, laengsterBlockSekunden = 252f, blockAnzahl = 2,
            spitzenKlasse = "Hammer", spitzenScore = 0.9f, einstufung = Einstufung.BAULAERM,
        )

        assertEquals(
            "Baulärm · 68% der Aufnahme · längster Block 4:12 · Spitze: Hämmern",
            formatiereBaulaermBefund(befund, testLabelMapping),
        )
    }

    @Test
    fun formatierungMoeglicherBaulaermOhneMappingBleibtBeimOriginalnamen() {
        val befund = BaulaermBefund(
            anteil = 0.05f, laengsterBlockSekunden = 2f, blockAnzahl = 1,
            spitzenKlasse = "Jackhammer", spitzenScore = 0.3f, einstufung = Einstufung.MOEGLICH,
        )

        assertEquals(
            "Möglicher Baulärm · 5% der Aufnahme · längster Block 0:02 · Spitze: Jackhammer",
            formatiereBaulaermBefund(befund, testLabelMapping),
        )
    }

    @Test
    fun formatierungKeinBaulaermUndUnklar() {
        val keinBaulaerm = BaulaermBefund(0f, 0f, 0, null, 0f, Einstufung.KEIN_BAULAERM)
        val unklar = BaulaermBefund(0f, 0f, 0, null, 0f, Einstufung.UNKLAR)

        assertEquals("Kein Baulärm erkannt", formatiereBaulaermBefund(keinBaulaerm, testLabelMapping))
        assertEquals("Unklar", formatiereBaulaermBefund(unklar, testLabelMapping))
    }

    @Test
    fun formatierungGelernteQuelleHatVorrangVorEinstufung() {
        val befund = BaulaermBefund(
            anteil = 0.02f, laengsterBlockSekunden = 0f, blockAnzahl = 0,
            spitzenKlasse = null, spitzenScore = 0f, einstufung = Einstufung.KEIN_BAULAERM,
            gelernteQuelle = "Baustelle Hofseite (91%)",
        )

        assertEquals("Gelernt: Baustelle Hofseite (91%)", formatiereBaulaermBefund(befund, testLabelMapping))
    }

    @Test
    fun formatiereDauerRandwerte() {
        assertEquals("0:00", formatiereDauer(0f))
        assertEquals("1:00", formatiereDauer(60f))
        assertEquals("1:12", formatiereDauer(72.4f))
    }
}
