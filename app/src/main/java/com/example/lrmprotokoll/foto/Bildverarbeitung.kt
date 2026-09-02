package com.example.lrmprotokoll.foto

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File
import java.security.MessageDigest

/**
 * Aufbereitung eines aufgenommenen Belegfotos: verkleinern, richtig herum drehen, Standortdaten
 * entfernen, Pruefsumme bilden.
 *
 * Benutzt bewusst `android.media.ExifInterface` aus dem Plattform-SDK statt
 * `androidx.exifinterface` - fuer das Auslesen eines einzigen Tags lohnt keine neue
 * Abhaengigkeit, und das Projekt haelt seinen Abhaengigkeitsstand ausdruecklich minimal (siehe
 * die KDoc-Begruendungen in DriveApiClient und MessreiheExport).
 *
 * Die Rechenschritte stehen als reine Funktionen daneben ([zielAbmessungen],
 * [inSampleSizeFuer], [drehungFuerExif]), damit sie ohne Bitmap, ohne Datei und ohne
 * Robolectric pruefbar sind - dasselbe Muster wie [com.example.lrmprotokoll.report.pdf.Seitenlauf].
 */
object Bildverarbeitung {

    /** Maximale Kantenlaenge des gespeicherten Fotos. */
    const val MAX_KANTE_PX = 1600

    /** JPEG-Qualitaet des gespeicherten Fotos. */
    const val JPEG_QUALITAET = 80

    /**
     * Zielabmessungen bei Begrenzung der laengeren Kante auf [maxKante], unter Beibehaltung des
     * Seitenverhaeltnisses.
     *
     * Kleinere Bilder werden NICHT vergroessert: Hochskalieren erfindet Bildinformation, die es
     * nicht gibt, und kostet nur Speicher.
     */
    fun zielAbmessungen(breite: Int, hoehe: Int, maxKante: Int = MAX_KANTE_PX): Pair<Int, Int> {
        if (breite <= 0 || hoehe <= 0) return breite to hoehe
        val laengsteKante = maxOf(breite, hoehe)
        if (laengsteKante <= maxKante) return breite to hoehe
        val faktor = maxKante.toDouble() / laengsteKante
        return maxOf(1, Math.round(breite * faktor).toInt()) to maxOf(1, Math.round(hoehe * faktor).toInt())
    }

    /**
     * `inSampleSize` fuer [BitmapFactory.Options] - die groesste Zweierpotenz, bei der das
     * dekodierte Bild noch mindestens [maxKante] gross bleibt.
     *
     * Ohne diesen Schritt muesste ein 12-Megapixel-Foto zuerst vollstaendig als Bitmap in den
     * Speicher (rund 48 MB bei ARGB_8888), bevor es verkleinert werden koennte - auf schwachen
     * Geraeten ist genau das der OutOfMemoryError.
     */
    fun inSampleSizeFuer(breite: Int, hoehe: Int, maxKante: Int = MAX_KANTE_PX): Int {
        if (breite <= 0 || hoehe <= 0) return 1
        var sample = 1
        while (maxOf(breite, hoehe) / (sample * 2) >= maxKante) {
            sample *= 2
        }
        return sample
    }

    /**
     * Drehung in Grad, die noetig ist, um ein Bild mit dem EXIF-Orientierungswert [exifOrientation]
     * aufrecht darzustellen.
     *
     * Viele Kameras speichern das Bild in Sensor-Orientierung und vermerken die Drehung nur im
     * EXIF-Tag. Wird das Bild neu geschrieben, ist der Tag weg - und das Foto liegt quer. Das ist
     * der mit Abstand haeufigste sichtbare Fehler bei genau dieser Funktion.
     */
    fun drehungFuerExif(exifOrientation: Int): Float = when (exifOrientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }

    /** SHA-256 als Hex, kleingeschrieben - dasselbe Format wie im Support-Bundle. */
    fun pruefsumme(datei: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        datei.inputStream().use { strom ->
            val puffer = ByteArray(8 * 1024)
            while (true) {
                val gelesen = strom.read(puffer)
                if (gelesen <= 0) break
                digest.update(puffer, 0, gelesen)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /**
     * Liest [quelle], verkleinert und dreht das Bild und schreibt es als JPEG nach [ziel].
     *
     * Standortdaten aus dem EXIF gehen dabei verloren - beabsichtigt. Die App fragt keine
     * Standortberechtigung fuer eigene Zwecke ab; ein Foto, das den Wohnort in die Drive-Cloud
     * traegt, waere eine stille Ausweitung, die niemand beauftragt hat.
     *
     * Liefert `false`, wenn das Bild nicht lesbar war. Wirft nicht: Ein misslungenes Foto darf
     * eine laufende Messung nicht gefaehrden.
     */
    fun verkleinereUndSpeichere(quelle: File, ziel: File, maxKante: Int = MAX_KANTE_PX): Boolean = runCatching {
        val masse = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(quelle.absolutePath, masse)
        if (masse.outWidth <= 0 || masse.outHeight <= 0) return false

        val optionen = BitmapFactory.Options().apply {
            inSampleSize = inSampleSizeFuer(masse.outWidth, masse.outHeight, maxKante)
        }
        val roh = BitmapFactory.decodeFile(quelle.absolutePath, optionen) ?: return false

        val drehung = runCatching {
            drehungFuerExif(
                ExifInterface(quelle.absolutePath)
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            )
        }.getOrDefault(0f)

        val (zielBreite, zielHoehe) = zielAbmessungen(roh.width, roh.height, maxKante)
        val skaliert = if (zielBreite != roh.width || zielHoehe != roh.height) {
            Bitmap.createScaledBitmap(roh, zielBreite, zielHoehe, true)
        } else {
            roh
        }

        val fertig = if (drehung != 0f) {
            Bitmap.createBitmap(skaliert, 0, 0, skaliert.width, skaliert.height, Matrix().apply { postRotate(drehung) }, true)
        } else {
            skaliert
        }

        ziel.outputStream().use { fertig.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITAET, it) }

        if (fertig !== skaliert) fertig.recycle()
        if (skaliert !== roh) skaliert.recycle()
        roh.recycle()
        true
    }.getOrDefault(false)
}
