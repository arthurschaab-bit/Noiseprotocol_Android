package com.example.lrmprotokoll.video

import java.io.File
import java.io.FileOutputStream

/**
 * Schreibt den Ton fuer ein laufendes Beweisvideo mit (M11 Etappe B, Owner-Entscheidung E9/V4).
 *
 * **Warum es diese Klasse ueberhaupt gibt:** Die Kamera nimmt bewusst *ohne* Tonspur auf, damit
 * sie das Mikrofon nicht anfasst und die Pegelmessung ungestoert weiterlaeuft. Der Ton kommt
 * deshalb aus derselben Aufnahmeschleife, die auch misst - und wird nach dem Stopp in die
 * MP4 einmultiplext.
 *
 * **Warum nicht die bestehende WAV-Aufzeichnung:** Die ist ereignisgebunden. `activeWavRecorder`
 * im [com.example.lrmprotokoll.audio.AudioRecordingService] existiert nur fuer die Dauer einer
 * Ereignisaufnahme (`recordDurationSeconds`); ein Video von drei Minuten bekaeme davon
 * hoechstens Bruchstuecke. Diese Senke laeuft unabhaengig davon und parallel dazu.
 *
 * Geschrieben wird rohes PCM ohne Header - genau das, was der AAC-Encoder im
 * [VideoMuxer] als Eingabe braucht. Ein WAV-Header waere ein Umweg ueber ein Format, das
 * anschliessend wieder aufgeschnitten werden muesste.
 *
 * Die Klasse ist absichtlich passiv: Sie kennt weder Kamera noch Datenbank und blockiert nie.
 * [schreibe] ist ein No-Op, solange nichts laeuft - der Aufruf steht im heissesten Pfad der App,
 * und die Aufnahmeschleife darf unter keinen Umstaenden stehenbleiben.
 */
class VideoTonMitschnitt {

    /** Was nach dem Stopp fuer den Mux-Lauf gebraucht wird. */
    data class Ergebnis(
        val datei: File,
        /** Wandzeit des ersten geschriebenen Blocks - Grundlage der A/V-Synchronisation. */
        val ersterBlockAm: Long,
        val abtastrate: Int,
        val kanaele: Int,
        val bytes: Long,
    )

    private class Laufend(
        val datei: File,
        val strom: FileOutputStream,
        val abtastrate: Int,
        val kanaele: Int,
        @Volatile var ersterBlockAm: Long = 0,
        @Volatile var bytes: Long = 0,
    )

    @Volatile private var laufend: Laufend? = null

    val laeuft: Boolean get() = laufend != null

    /**
     * Beginnt den Mitschnitt. Liefert `false`, wenn bereits einer laeuft oder die Datei nicht
     * angelegt werden kann - der Aufrufer entscheidet dann, ob er das Video stumm aufnimmt.
     *
     * [abtastrate] MUSS die vom `AudioRecord` tatsaechlich ausgehandelte Rate sein, nicht die
     * eingestellte: Weichen sie voneinander ab, waere der spaeter eingemuxte Ton in falscher
     * Tonhoehe und falscher Laenge.
     */
    fun starte(ziel: File, abtastrate: Int, kanaele: Int): Boolean {
        if (laufend != null) return false
        return try {
            laufend = Laufend(ziel, FileOutputStream(ziel), abtastrate, kanaele)
            true
        } catch (e: Throwable) {
            laufend = null
            false
        }
    }

    /**
     * Nimmt einen PCM-Block auf. No-Op ohne laufenden Mitschnitt.
     *
     * Ein Schreibfehler beendet den Mitschnitt still, statt zu werfen: Der Aufrufer ist die
     * Aufnahmeschleife, und die Messung ist die Kernaufgabe - ein voller Speicher darf das
     * Beweisvideo kosten, niemals die Messreihe.
     */
    fun schreibe(daten: ByteArray, laenge: Int, jetzt: Long) {
        val aktiv = laufend ?: return
        try {
            if (aktiv.bytes == 0L) aktiv.ersterBlockAm = jetzt
            aktiv.strom.write(daten, 0, laenge)
            aktiv.bytes += laenge
        } catch (e: Throwable) {
            runCatching { aktiv.strom.close() }
            laufend = null
        }
    }

    /**
     * Beendet den Mitschnitt und liefert, was der Mux-Lauf braucht. `null`, wenn nichts lief
     * oder kein einziger Block ankam - dann gibt es keinen Ton, und das Video bleibt stumm.
     */
    fun beende(): Ergebnis? {
        val aktiv = laufend ?: return null
        laufend = null
        runCatching { aktiv.strom.close() }
        if (aktiv.bytes == 0L) return null
        return Ergebnis(
            datei = aktiv.datei,
            ersterBlockAm = aktiv.ersterBlockAm,
            abtastrate = aktiv.abtastrate,
            kanaele = aktiv.kanaele,
            bytes = aktiv.bytes,
        )
    }

    /** Bricht ab und raeumt die Datei weg - fuer den Fall, dass die Videoaufnahme scheitert. */
    fun verwerfe() {
        val aktiv = laufend ?: return
        laufend = null
        runCatching { aktiv.strom.close() }
        runCatching { aktiv.datei.delete() }
    }
}
