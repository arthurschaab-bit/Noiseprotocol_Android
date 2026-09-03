package com.example.lrmprotokoll.video

import java.util.Locale

/**
 * Die Entscheidungen rund um eine Videoaufnahme, die sich ohne Kamera pruefen lassen
 * (M11 Etappe B, B.5).
 *
 * Bewusst getrennt vom Aufnahme-Screen: Ob eine Aufnahme wegen zu wenig Speicher gar nicht erst
 * beginnt, ist eine Regel und keine Oberflaeche - und sie muss stimmen. Ein mitten im Beweis
 * abbrechendes Video ist schlimmer als eines, das nie begonnen hat.
 */
object Videospeicher {

    /**
     * Unter dieser Grenze wird nicht aufgenommen. 500 MB klingen viel fuer einen Clip von drei
     * Minuten - der Puffer ist Absicht: Auf dem Geraet laufen parallel die WAV-Ereignisse, die
     * Messreihe und der Mux-Lauf, der voruebergehend Video, PCM und Zieldatei gleichzeitig
     * vorhaelt.
     */
    const val MINDESTSPEICHER_BYTES = 500L * 1024 * 1024

    fun reichtSpeicher(freieBytes: Long): Boolean = freieBytes >= MINDESTSPEICHER_BYTES

    /**
     * Grobe Schaetzung des Platzbedarfs. 720p liegt je nach Bewegung bei rund 8 Mbit/s, 1080p bei
     * rund 16 Mbit/s. Bewusst nur als Hinweis in der Oberflaeche verwendet - keine Zahl, auf die
     * sich eine Zusage stuetzt.
     */
    fun geschaetzteGroesseBytes(dauerSekunden: Int, aufloesung: String): Long {
        val bitsProSekunde = if (aufloesung == "FHD") 16_000_000L else 8_000_000L
        return dauerSekunden.coerceAtLeast(0) * bitsProSekunde / 8
    }

    /** Dateiname eines Videos - sortierbar und ohne Doppelung, wie bei den WAV-Ereignissen. */
    fun dateiname(zeitpunktMs: Long, gemuxt: Boolean = false): String {
        val format = java.text.SimpleDateFormat("yyyyMMdd_HH_mm_ss", Locale.US)
        val suffix = if (gemuxt) "_ton" else ""
        return "video_${format.format(java.util.Date(zeitpunktMs))}$suffix.mp4"
    }

    /** Name der PCM-Begleitdatei zu einem Video - gleiche Basis, damit Paare erkennbar bleiben. */
    fun tondateiname(zeitpunktMs: Long): String {
        val format = java.text.SimpleDateFormat("yyyyMMdd_HH_mm_ss", Locale.US)
        return "video_${format.format(java.util.Date(zeitpunktMs))}.pcm"
    }

    /** "2:59" - fuer die laufende Dauer und die Restzeit im Aufnahme-Screen. */
    fun formatiereDauer(sekunden: Long): String {
        val s = sekunden.coerceAtLeast(0)
        return String.format(Locale.GERMANY, "%d:%02d", s / 60, s % 60)
    }
}
