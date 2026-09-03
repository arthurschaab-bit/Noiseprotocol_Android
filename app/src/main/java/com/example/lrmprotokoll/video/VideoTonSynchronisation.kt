package com.example.lrmprotokoll.video

/**
 * Rechnet aus, wie der separat mitgeschnittene Ton zum Video passt (M11 Etappe B, B.2a
 * Schritt 3).
 *
 * Bewusst als reine Funktion ohne Android-Bezug: Der eigentliche Mux-Lauf haengt an
 * `MediaCodec`/`MediaMuxer` und ist in dieser Umgebung nicht ausfuehrbar - die Rechnung, bei der
 * sich ein Fehler als "Knall eine Sekunde neben dem Bild" zeigt, ist es sehr wohl. Dasselbe
 * Muster wie [com.example.lrmprotokoll.report.pdf.Seitenlauf] zum untestbaren `PdfDocument`.
 */
object VideoTonSynchronisation {

    /** 16-Bit-PCM. Ein Frame umfasst alle Kanaele eines Abtastzeitpunkts. */
    const val BYTES_PRO_SAMPLE = 2

    fun bytesProFrame(kanaele: Int): Int = BYTES_PRO_SAMPLE * kanaele.coerceAtLeast(1)

    /**
     * Wie der Ton an den Videoanfang angelegt wird.
     *
     * @param ueberspringeBytes Bytes, die am Anfang der PCM-Datei wegfallen - der Ton begann vor
     * dem Video.
     * @param stilleBytes Nullbytes, die dem Ton vorangestellt werden - der Ton begann nach dem
     * Video. Stille voranstellen statt den Ton vorzuziehen: Ein Beweisvideo, in dem das
     * Geraeusch vor seiner Ursache zu hoeren ist, ist als Beweis wertlos.
     */
    data class Anlage(val ueberspringeBytes: Long, val stilleBytes: Long)

    /**
     * @param videoStartMs Wandzeit, zu der die Videoaufnahme begann.
     * @param tonStartMs Wandzeit des ersten mitgeschnittenen PCM-Blocks.
     *
     * Immer auf ganze Frames gerundet: Ein um ein einzelnes Byte verschobener Stereostrom
     * vertauscht die Kanaele und klingt kaputt.
     */
    fun anlage(videoStartMs: Long, tonStartMs: Long, abtastrate: Int, kanaele: Int): Anlage {
        require(abtastrate > 0) { "Abtastrate muss positiv sein" }
        val proFrame = bytesProFrame(kanaele)
        val versatzMs = videoStartMs - tonStartMs
        val bytes = aufFrameGerundet(versatzMs, abtastrate, proFrame)
        return if (versatzMs >= 0) Anlage(ueberspringeBytes = bytes, stilleBytes = 0)
        else Anlage(ueberspringeBytes = 0, stilleBytes = bytes)
    }

    private fun aufFrameGerundet(versatzMs: Long, abtastrate: Int, proFrame: Int): Long {
        val frames = Math.abs(versatzMs) * abtastrate / 1000L
        return frames * proFrame
    }

    /**
     * Praesentationszeitstempel eines PCM-Blocks, gerechnet aus der **Sample-Position** - nicht
     * aus der Uhr. Mit `System.nanoTime()` wanderte der Ton mit jeder Verzoegerung im Encoder.
     */
    fun zeitstempelMikros(bytePosition: Long, abtastrate: Int, kanaele: Int): Long {
        require(abtastrate > 0) { "Abtastrate muss positiv sein" }
        val frames = bytePosition / bytesProFrame(kanaele)
        return frames * 1_000_000L / abtastrate
    }

    /** Dauer eines PCM-Stroms in Millisekunden - fuer Diagnose und Plausibilitaetspruefung. */
    fun dauerMs(bytes: Long, abtastrate: Int, kanaele: Int): Long {
        require(abtastrate > 0) { "Abtastrate muss positiv sein" }
        val frames = bytes / bytesProFrame(kanaele)
        return frames * 1000L / abtastrate
    }
}
