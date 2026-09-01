package com.example.lrmprotokoll.audio

import android.media.MediaRecorder

/**
 * KI-Umbau Etappe 1.2: `UNPROCESSED`, wenn das Geraet es unterstuetzt (kein AGC, keine
 * Rauschunterdrueckung, kein Hochpassfilter), sonst `MIC`.
 * `VOICE_RECOGNITION`/`VOICE_COMMUNICATION`/`CAMCORDER` sind bewusst ausgeschlossen: sie
 * aktivieren genau die Signalverarbeitung, die Impulslaerm-Merkmale wegnormalisiert.
 *
 * Reine Funktion - `unterstuetztUnprocessed` kommt vom Aufrufer
 * (`AudioManager.getProperty(PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"`), damit dies
 * ohne Android-Kontext testbar bleibt.
 */
internal fun waehleAufnahmequelle(unterstuetztUnprocessed: Boolean): Int =
    if (unterstuetztUnprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC

/**
 * Aufsteigend sortierte, in der Praxis von Android-Geraeten haeufig unterstuetzte Abtastraten -
 * die Kandidatenleiter fuer [waehleAufnahmerate], falls die gewuenschte Rate nicht unterstuetzt
 * wird.
 */
internal val AUFNAHMERATE_KANDIDATEN = intArrayOf(11025, 16000, 22050, 24000, 32000, 44100, 48000)

/**
 * KI-Umbau Etappe 1.2: waehlt [gewuenschteRate], falls unterstuetzt - sonst die naechsthoehere
 * unterstuetzte Rate aus [AUFNAHMERATE_KANDIDATEN]. NIEMALS eine niedrigere Rate als
 * [gewuenschteRate]: YAMNets Mel-Spektrogramm deckt 125-7500 Hz ab, eine niedrigere Abtastrate
 * wuerde genau die Frequenzbaender kappen, die Baulaerm von anderen Geraeuschen unterscheidbar
 * machen. Ist auch keine hoehere Rate unterstuetzt, bleibt [gewuenschteRate] als letzter Ausweg
 * bestehen (kein Sturz auf eine niedrigere Rate).
 *
 * Reine Funktion - `istUnterstuetzt` kommt vom Aufrufer (typischerweise ueber
 * `AudioRecord.getMinBufferSize()`), damit dies ohne echtes Audio-Hardware-Handling testbar
 * bleibt.
 */
internal fun waehleAufnahmerate(gewuenschteRate: Int, istUnterstuetzt: (Int) -> Boolean): Int {
    if (istUnterstuetzt(gewuenschteRate)) return gewuenschteRate
    return AUFNAHMERATE_KANDIDATEN
        .filter { it > gewuenschteRate }
        .sorted()
        .firstOrNull(istUnterstuetzt)
        ?: gewuenschteRate
}
