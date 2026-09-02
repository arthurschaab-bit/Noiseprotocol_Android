package com.example.lrmprotokoll.audio

import android.util.Log
import java.io.File

/**
 * Abstraktion ueber die KI-Geraeuschklassifikation (B-11): [AudioRecordingService] haengt nur
 * von dieser Schnittstelle ab, nicht von der konkreten MediaPipe-Implementierung - dadurch
 * laesst sich in Tests eine fehlgeschlagene Initialisierung simulieren, ohne ein echtes Modell
 * oder native Bibliotheken zu benoetigen.
 */
interface SoundClassifier {
    /**
     * Liefert das erkannte Label, oder null, wenn nichts Brauchbares erkannt wurde ODER die
     * Klassifikation aus irgendeinem Grund nicht verfuegbar ist. Beides ist fuer den Aufrufer
     * bewusst ununterscheidbar: die Aufnahme muss in jedem Fall weiterlaufen, ein Lärmprotokoll
     * ohne Klassifikation ist brauchbar, eines ohne Aufnahme nicht (Prompt B-11, Problem 2).
     */
    fun classify(file: File): String?
}

/**
 * KI-Umbau Etappe 1.4: wie [SoundClassifier], liefert aber zusaetzlich die Rohdaten fuer die
 * Persistenz ([KlassifikationsErgebnis.rohdaten]) - eigene, schlanke Schnittstelle statt die
 * konkrete [NoiseClassifier]-Klasse direkt zu verwenden, damit [klassifiziereUndSpeichere] und
 * [AudioRecordingService] weiterhin mit einem handgeschriebenen Fake testbar bleiben (dieses
 * Projekt nutzt bewusst kein Mocking-Framework - [NoiseClassifier] selbst ist nicht `open` und
 * braucht in seinem Konstruktor einen echten Android-[android.content.Context]).
 */
interface RohdatenClassifier {
    fun klassifiziereMitRohdaten(file: File): KlassifikationsErgebnis?
}

/**
 * Ruft [classifier] nur auf, wenn die KI-Erkennung aktiviert ist, und faengt dabei JEDE
 * Ausnahme ab - nicht nur die von [classifier] selbst schon behandelten Faelle, sondern auch
 * einen komplett fehlgeschlagenen (null) Klassifikator oder eine unerwartete Ausnahme an der
 * Aufrufstelle. So bleibt die Garantie "Klassifikation kann die Aufnahme nicht verhindern" auch
 * dann bestehen, wenn eine zukuenftige Implementierung diese Absicherung selbst vergisst.
 */
fun classifySafely(classifier: SoundClassifier?, aiEnabled: Boolean, file: File): String? {
    if (!aiEnabled || classifier == null) return null
    return try {
        classifier.classify(file)
    } catch (e: Throwable) {
        Log.w("AudioRecordingService", "Klassifikation fehlgeschlagen, Aufnahme laeuft trotzdem weiter", e)
        null
    }
}
