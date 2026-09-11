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
     *
     * Vor jedem echten Block wird geprueft, ob die bislang geschriebene PCM-Menge hinter der seit
     * [Laufend.ersterBlockAm] tatsaechlich vergangenen Wanduhrzeit zurueckbleibt (Prüfprotokoll-
     * Frage 9, Owner-Entscheidung vom 11.09.2026: "immer und synchron") - fehlt etwas, wird die
     * Differenz als Stille nachgetragen, BEVOR der neue Block geschrieben wird. Ohne das
     * verkuerzte jeder Aussetzer der Aufnahmeschleife (Doze, Scheduler, ein Schreibfehler
     * anderswo im selben Lese-/Schreibdurchlauf) die Tonspur, ohne dass die Zeitachse davon
     * erfaehrt - ALLES danach Aufgezeichnete rutschte im fertigen Video nach vorn, der Ton lief
     * dem Bild zunehmend voraus. Mit dem Nachtrag bleibt jeder neue Block an der Stelle, die
     * seiner tatsaechlichen Wanduhrzeit entspricht.
     */
    fun schreibe(daten: ByteArray, laenge: Int, jetzt: Long) {
        val aktiv = laufend ?: return
        try {
            if (aktiv.bytes == 0L) {
                aktiv.ersterBlockAm = jetzt
            } else {
                fuelleLueckeMitStille(aktiv, jetzt)
            }
            aktiv.strom.write(daten, 0, laenge)
            aktiv.bytes += laenge
        } catch (e: Throwable) {
            runCatching { aktiv.strom.close() }
            laufend = null
        }
    }

    /** Toleranz, unterhalb derer keine Stille nachgetragen wird - der normale Jitter zwischen
     * zwei `AudioRecord.read()`-Aufrufen soll nicht staendig winzige Stille-Schnipsel erzeugen. */
    private val LUECKEN_TOLERANZ_MS = 100L

    /** Obergrenze fuer einen einzelnen Schreibvorgang beim Nachtragen - begrenzt den Speicher bei
     * einer sehr grossen Luecke (z.B. Geraet laenger im Deep Sleep) auf einen festen Puffer,
     * statt in einem Zug ein ByteArray in Groesse der gesamten Luecke zu allozieren. */
    private val STILLE_PUFFER_BYTES = 65_536

    private fun fuelleLueckeMitStille(aktiv: Laufend, jetzt: Long) {
        val vergangeneMs = jetzt - aktiv.ersterBlockAm
        if (vergangeneMs <= 0) return
        val bytesProSekunde = VideoTonSynchronisation.bytesProFrame(aktiv.kanaele).toLong() * aktiv.abtastrate
        val erwarteteBytes = bytesProSekunde * vergangeneMs / 1000L
        var fehlendeBytes = erwarteteBytes - aktiv.bytes
        // Auf ganze Frames runden - ein einzelnes Byte Versatz vertauscht bei Stereo die Kanaele.
        fehlendeBytes -= fehlendeBytes % VideoTonSynchronisation.bytesProFrame(aktiv.kanaele)
        if (fehlendeBytes < bytesProSekunde * LUECKEN_TOLERANZ_MS / 1000L) return

        val stillePuffer = ByteArray(minOf(fehlendeBytes, STILLE_PUFFER_BYTES.toLong()).toInt())
        while (fehlendeBytes > 0) {
            val schreibLaenge = minOf(fehlendeBytes, stillePuffer.size.toLong()).toInt()
            aktiv.strom.write(stillePuffer, 0, schreibLaenge)
            aktiv.bytes += schreibLaenge
            fehlendeBytes -= schreibLaenge
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
