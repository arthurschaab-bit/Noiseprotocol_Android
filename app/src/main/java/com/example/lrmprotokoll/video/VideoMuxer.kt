package com.example.lrmprotokoll.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer

private const val AAC_BITRATE = 128_000
private const val PCM_BLOCK = 8192
private const val CODEC_TIMEOUT_US = 10_000L

/**
 * Fuegt einem stumm aufgenommenen Beweisvideo die Tonspur hinzu (M11 Etappe B, B.2a).
 *
 * Der Weg in zwei Schritten - PCM erst nach AAC encodieren, dann Video- und Tonspur
 * zusammenfuehren - ist bewusst gewaehlt: [MediaMuxer] nimmt nur bereits encodierte Samples und
 * braucht beim Anlegen der Tonspur das vom Encoder erzeugte Format samt `csd`. Wer beides in
 * einem Durchgang macht, muss entweder den gesamten encodierten Ton im Speicher halten oder den
 * AAC-Header von Hand bauen. Der Umweg ueber eine ADTS-Zwischendatei kostet etwas Platz und
 * spart beides.
 *
 * Nichts davon laeuft ausserhalb eines Android-Geraets: `MediaCodec` und `MediaMuxer` sind
 * Plattformcode ohne JVM-Implementierung. Die Rechnung, bei der ein Fehler nicht auffiele,
 * steckt deshalb in [VideoTonSynchronisation] und ist dort einzeln getestet.
 */
class VideoMuxer {

    /**
     * @return die Groesse der fertigen Datei in Bytes.
     *
     * Wirft bei jedem Fehler. Der Aufrufer behaelt dann stumme MP4 und PCM-Datei: Ein stummes
     * Video plus separate Tondatei ist immer noch ein Beweismittel, ein geloeschtes
     * Zwischenergebnis ist keines.
     */
    fun fuegeTonHinzu(
        stummesVideo: File,
        pcm: File,
        ziel: File,
        videoStartMs: Long,
        tonStartMs: Long,
        abtastrate: Int,
        kanaele: Int,
    ): Long {
        val anlage = VideoTonSynchronisation.anlage(videoStartMs, tonStartMs, abtastrate, kanaele)
        val aac = File(ziel.parentFile, "${ziel.nameWithoutExtension}.aac")
        try {
            encodiereNachAdts(pcm, aac, abtastrate, kanaele, anlage)
            fuehreZusammen(stummesVideo, aac, ziel)
            return ziel.length()
        } finally {
            // Die AAC-Zwischendatei ist reines Beiwerk - anders als PCM und stumme MP4, die im
            // Fehlerfall ausdruecklich erhalten bleiben.
            runCatching { aac.delete() }
        }
    }

    // ------------------------------------------------------------------ Schritt 2: PCM -> AAC

    private fun encodiereNachAdts(
        pcm: File,
        ziel: File,
        abtastrate: Int,
        kanaele: Int,
        anlage: VideoTonSynchronisation.Anlage,
    ) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, abtastrate, kanaele).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AAC_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, PCM_BLOCK)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val info = MediaCodec.BufferInfo()
        var gelesen = 0L

        try {
            FileOutputStream(ziel).use { ausgabe ->
                pcm.inputStream().use { eingabe ->
                    ueberspringe(eingabe, anlage.ueberspringeBytes)
                    var stilleUebrig = anlage.stilleBytes
                    var eingabeFertig = false

                    while (true) {
                        if (!eingabeFertig) {
                            val index = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                            if (index >= 0) {
                                val puffer = encoder.getInputBuffer(index)!!
                                puffer.clear()
                                val block = ByteArray(minOf(PCM_BLOCK.toLong(), puffer.capacity().toLong()).toInt())

                                // Erst die vorangestellte Stille, dann die echten Samples: Der Ton
                                // begann spaeter als das Video, und Stille ist die ehrliche
                                // Fuellung fuer eine Zeit, in der nichts aufgezeichnet wurde.
                                val menge = if (stilleUebrig > 0) {
                                    val n = minOf(stilleUebrig, block.size.toLong()).toInt()
                                    java.util.Arrays.fill(block, 0, n, 0)
                                    stilleUebrig -= n
                                    n
                                } else {
                                    eingabe.read(block).coerceAtLeast(0)
                                }

                                if (menge == 0) {
                                    encoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    eingabeFertig = true
                                } else {
                                    puffer.put(block, 0, menge)
                                    val zeit = VideoTonSynchronisation.zeitstempelMikros(gelesen, abtastrate, kanaele)
                                    encoder.queueInputBuffer(index, 0, menge, zeit, 0)
                                    gelesen += menge
                                }
                            }
                        }

                        val ausIndex = encoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
                        if (ausIndex >= 0) {
                            val puffer = encoder.getOutputBuffer(ausIndex)!!
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                                schreibeAdtsRahmen(ausgabe, puffer, info.offset, info.size, abtastrate, kanaele)
                            }
                            encoder.releaseOutputBuffer(ausIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        } else if (ausIndex == MediaCodec.INFO_TRY_AGAIN_LATER && eingabeFertig) {
                            // Nur weiterdrehen; der Encoder liefert das Ende noch.
                            continue
                        }
                    }
                }
            }
        } finally {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
        }
    }

    private fun ueberspringe(strom: InputStream, bytes: Long) {
        var offen = bytes
        while (offen > 0) {
            val n = strom.skip(offen)
            // skip() darf 0 liefern, ohne dass das Ende erreicht ist - eine Endlosschleife waere
            // hier die schlechtere Antwort als ein Fehler.
            if (n <= 0) throw java.io.IOException("Konnte $bytes Bytes nicht ueberspringen")
            offen -= n
        }
    }

    /**
     * ADTS-Rahmen von 7 Byte vor jedes AAC-Paket. Ohne ihn ist die Zwischendatei fuer den
     * [MediaExtractor] im naechsten Schritt nicht lesbar - er kennt weder Rate noch Kanalzahl.
     */
    private fun schreibeAdtsRahmen(
        ausgabe: FileOutputStream,
        puffer: ByteBuffer,
        offset: Int,
        groesse: Int,
        abtastrate: Int,
        kanaele: Int,
    ) {
        val gesamt = groesse + 7
        val kopf = ByteArray(7)
        val rateIndex = adtsRateIndex(abtastrate)
        kopf[0] = 0xFF.toByte()
        kopf[1] = 0xF1.toByte() // MPEG-4, Layer 0, kein CRC
        kopf[2] = (((1 /* AAC-LC */ shl 6) or (rateIndex shl 2) or (kanaele shr 2))).toByte()
        kopf[3] = (((kanaele and 3) shl 6) or (gesamt shr 11)).toByte()
        kopf[4] = ((gesamt and 0x7FF) shr 3).toByte()
        kopf[5] = (((gesamt and 7) shl 5) or 0x1F).toByte()
        kopf[6] = 0xFC.toByte()
        ausgabe.write(kopf)

        val daten = ByteArray(groesse)
        puffer.position(offset)
        puffer.get(daten, 0, groesse)
        ausgabe.write(daten)
    }

    private fun adtsRateIndex(abtastrate: Int): Int {
        val raten = intArrayOf(
            96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350,
        )
        val index = raten.indexOf(abtastrate)
        // Eine Rate, die ADTS nicht kennt, waere im Header eine falsche Tatsachenbehauptung -
        // das Ergebnis klaenge in falscher Tonhoehe, ohne dass irgendetwas fehlschluege.
        if (index < 0) throw IllegalArgumentException("Abtastrate $abtastrate ist in ADTS nicht darstellbar")
        return index
    }

    // ---------------------------------------------------------- Schritt 3: Spuren zusammenfuehren

    private fun fuehreZusammen(stummesVideo: File, aac: File, ziel: File) {
        val videoQuelle = MediaExtractor().apply { setDataSource(stummesVideo.absolutePath) }
        val tonQuelle = MediaExtractor().apply { setDataSource(aac.absolutePath) }
        val muxer = MediaMuxer(ziel.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        try {
            val videoSpur = waehleSpur(videoQuelle, "video/")
                ?: throw IllegalStateException("Die Aufnahme enthaelt keine Videospur")
            val tonSpur = waehleSpur(tonQuelle, "audio/")
                ?: throw IllegalStateException("Die Tondatei enthaelt keine Audiospur")

            val videoZiel = muxer.addTrack(videoQuelle.getTrackFormat(videoSpur))
            val tonZiel = muxer.addTrack(tonQuelle.getTrackFormat(tonSpur))
            muxer.start()

            kopiere(videoQuelle, muxer, videoZiel)
            kopiere(tonQuelle, muxer, tonZiel)

            muxer.stop()
        } finally {
            runCatching { muxer.release() }
            runCatching { videoQuelle.release() }
            runCatching { tonQuelle.release() }
        }
    }

    private fun waehleSpur(quelle: MediaExtractor, praefix: String): Int? {
        for (i in 0 until quelle.trackCount) {
            val typ = quelle.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
            if (typ.startsWith(praefix)) {
                quelle.selectTrack(i)
                return i
            }
        }
        return null
    }

    private fun kopiere(quelle: MediaExtractor, muxer: MediaMuxer, zielSpur: Int) {
        val puffer = ByteBuffer.allocate(1 shl 20)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val groesse = quelle.readSampleData(puffer, 0)
            if (groesse < 0) break
            info.offset = 0
            info.size = groesse
            info.presentationTimeUs = quelle.sampleTime
            info.flags = quelle.sampleFlags
            muxer.writeSampleData(zielSpur, puffer, info)
            quelle.advance()
        }
    }
}
