package com.example.lrmprotokoll.messreihe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TAG_MS = 24L * 60 * 60 * 1000

/**
 * Die Auswahl- und Zaehllogik der Speicherverwaltung (Owner-Entscheidung E8). Bewusst ohne
 * Dateisystem: Was hier falsch ist, loescht beim Nutzer Beweismaterial - das muss ohne Geraet
 * und ohne Robolectric pruefbar sein.
 */
class SpeicheraufraeumerTest {

    private val jetzt = 1_800_000_000_000L

    private fun datei(name: String, bytes: Long = 1000, alterTage: Long = 30) =
        Speicherdatei(name, bytes, jetzt - alterTage * TAG_MS)

    // ------------------------------------------------------------------ Zuordnung

    @Test
    fun jedeDateiartLandetInIhrerKategorie() {
        assertEquals(Speicherkategorie.AUDIO, Speicheraufraeumer.kategorieFuer("20260903_08_00_00_1.wav"))
        assertEquals(Speicherkategorie.VIDEO, Speicheraufraeumer.kategorieFuer("video_20260903_08_00_00.mp4"))
        assertEquals(Speicherkategorie.FOTO, Speicheraufraeumer.kategorieFuer("foto_1.jpg"))
        assertEquals(Speicherkategorie.FOTO, Speicheraufraeumer.kategorieFuer("foto_1.JPEG"))
    }

    @Test
    fun dieTonspurEinesVideosGehoertZumVideo() {
        // Bleibt liegen, wenn ein Mux-Lauf fehlschlaegt - und muss dann mit dem Video
        // freigegeben werden koennen.
        assertEquals(Speicherkategorie.VIDEO, Speicheraufraeumer.kategorieFuer("video_20260903_08_00_00.pcm"))
    }

    @Test
    fun berichteUndUnbekanntesGehoerenInKeineLoeschbareKategorie() {
        // Berichte sind das Ergebnis der Arbeit, nicht ihr Rohmaterial.
        assertNull(Speicheraufraeumer.kategorieFuer("Tagesbericht_01.01.2026.pdf"))
        assertNull(Speicheraufraeumer.kategorieFuer("messreihe.csv"))
        assertNull(Speicheraufraeumer.kategorieFuer("ohne_endung"))
    }

    // ------------------------------------------------------------------ Anzeige

    @Test
    fun dieBelegungWirdNachKategorienGetrenntGezaehlt() {
        val belegung = Speicheraufraeumer.fasseZusammen(
            listOf(
                datei("a.wav", bytes = 1000),
                datei("b.wav", bytes = 2000),
                datei("v.mp4", bytes = 50_000),
                datei("f.jpg", bytes = 300),
                datei("bericht.pdf", bytes = 7000),
            ),
            datenbankBytes = 4000,
            freiBytes = 999_000,
        )

        assertEquals(2, belegung.posten(Speicherkategorie.AUDIO).anzahl)
        assertEquals(3000L, belegung.posten(Speicherkategorie.AUDIO).bytes)
        assertEquals(50_000L, belegung.posten(Speicherkategorie.VIDEO).bytes)
        assertEquals(300L, belegung.posten(Speicherkategorie.FOTO).bytes)
        assertEquals(1, belegung.sonstigesAnzahl)
        assertEquals(7000L, belegung.sonstigesBytes)
    }

    @Test
    fun dieSummeGehtAuf() {
        // Wenn die angezeigten Zahlen sich nicht zur Gesamtbelegung addieren, sucht der Nutzer
        // den Unterschied vergeblich - deshalb gibt es "Sonstiges" ueberhaupt.
        val belegung = Speicheraufraeumer.fasseZusammen(
            listOf(datei("a.wav", 1000), datei("v.mp4", 2000), datei("x.pdf", 500)),
            datenbankBytes = 250,
            freiBytes = 0,
        )
        assertEquals(3750L, belegung.gesamtBytes)
    }

    @Test
    fun eineLeereKategorieWirdMitNullAngezeigtNichtVerschwiegen() {
        val belegung = Speicheraufraeumer.fasseZusammen(emptyList(), 0, 0)
        assertEquals(Speicherkategorie.entries.size, belegung.posten.size)
        assertEquals(0, belegung.posten(Speicherkategorie.VIDEO).anzahl)
    }

    // ------------------------------------------------------------------ Auswahl

    @Test
    fun ohneKategorieWirdNichtsAusgewaehlt() {
        val treffer = Speicheraufraeumer.waehleAus(
            listOf(datei("a.wav")), emptySet(), aelterAlsTage = null, jetzt = jetzt,
        )
        assertTrue(treffer.isEmpty())
    }

    @Test
    fun nurDieGewaehltenKategorienWerdenGetroffen() {
        val dateien = listOf(datei("a.wav"), datei("v.mp4"), datei("f.jpg"))

        val nurAudio = Speicheraufraeumer.waehleAus(dateien, setOf(Speicherkategorie.AUDIO), null, jetzt)
        assertEquals(listOf("a.wav"), nurAudio.map { it.name })

        val audioUndVideo = Speicheraufraeumer.waehleAus(
            dateien, setOf(Speicherkategorie.AUDIO, Speicherkategorie.VIDEO), null, jetzt,
        )
        assertEquals(listOf("a.wav", "v.mp4"), audioUndVideo.map { it.name })
    }

    @Test
    fun dieAltersgrenzeSchliesstJuengereDateienAus() {
        val dateien = listOf(
            datei("alt.wav", alterTage = 40),
            datei("grenzwertig.wav", alterTage = 30),
            datei("jung.wav", alterTage = 10),
        )

        val treffer = Speicheraufraeumer.waehleAus(dateien, setOf(Speicherkategorie.AUDIO), 30, jetzt)

        assertEquals(listOf("alt.wav"), treffer.map { it.name })
    }

    @Test
    fun ohneAltersgrenzeWirdAllesAelterAlsDieSchonfristGetroffen() {
        val dateien = listOf(datei("alt.wav", alterTage = 40), datei("gestern.wav", alterTage = 1))

        val treffer = Speicheraufraeumer.waehleAus(dateien, setOf(Speicherkategorie.AUDIO), null, jetzt)

        assertEquals(2, treffer.size)
    }

    @Test
    fun eineGeradeLaufendeAufnahmeWirdNiemalsGeloescht() {
        // Der Kern der Schonfrist: Waehrend einer Messung schreibt der Dienst gerade an einer
        // Ereignis-WAV. "Alles loeschen" darf die laufende Aufnahme nicht zerstoeren.
        val dateien = listOf(
            Speicherdatei("laeuft_gerade.wav", 1000, jetzt - 10_000),
            Speicherdatei("fertig.wav", 1000, jetzt - Speicheraufraeumer.SCHONFRIST_MS - 1),
        )

        val treffer = Speicheraufraeumer.waehleAus(dateien, setOf(Speicherkategorie.AUDIO), null, jetzt)

        assertEquals(listOf("fertig.wav"), treffer.map { it.name })
    }

    @Test
    fun dieSchonfristGiltAuchFuerEinLaufendesVideo() {
        // MP4 und PCM sind waehrend des Mux-Laufs beide offen.
        val dateien = listOf(
            Speicherdatei("video_neu.mp4", 1000, jetzt - 30_000),
            Speicherdatei("video_neu.pcm", 1000, jetzt - 30_000),
        )

        val treffer = Speicheraufraeumer.waehleAus(dateien, setOf(Speicherkategorie.VIDEO), null, jetzt)

        assertTrue(treffer.isEmpty())
    }

    @Test
    fun eineUnsinnigeAltersgrenzeWirdWieKeineBehandelt() {
        // 0 oder negativ waere sonst "alles inklusive der laufenden Aufnahme".
        val dateien = listOf(datei("alt.wav", alterTage = 40), Speicherdatei("neu.wav", 1000, jetzt))

        val treffer = Speicheraufraeumer.waehleAus(dateien, setOf(Speicherkategorie.AUDIO), 0, jetzt)

        assertEquals(listOf("alt.wav"), treffer.map { it.name })
    }

    @Test
    fun berichteWerdenAuchDannNichtGeloeschtWennAllesGewaehltIst() {
        val dateien = listOf(datei("bericht.pdf", alterTage = 400), datei("a.wav", alterTage = 400))

        val treffer = Speicheraufraeumer.waehleAus(
            dateien, Speicherkategorie.entries.toSet(), null, jetzt,
        )

        assertEquals(listOf("a.wav"), treffer.map { it.name })
    }
}
