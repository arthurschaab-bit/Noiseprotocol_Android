package com.example.lrmprotokoll.audio

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Prompt B-11, Problem 2: eine fehlgeschlagene Klassifikator-Initialisierung oder eine
 * unerwartete Ausnahme aus dem Klassifikator darf die Aufnahme nicht verhindern. classifySafely()
 * ist die Stelle, die diese Garantie durchsetzt - getestet ueber eine Fake-Implementierung von
 * [SoundClassifier], nicht ueber das echte MediaPipe-Modell (Prompt: "NoiseClassifier
 * injizierbar oder hinter eine kleine Schnittstelle legen").
 *
 * Robolectric statt reinem JUnit, weil classifySafely() im Fehlerfall echtes android.util.Log
 * durchlaeuft.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SoundClassifierTest {

    private val file = File("irrelevant.wav")

    private class FakeSoundClassifier(
        private val result: String? = null,
        private val throwable: Throwable? = null,
    ) : SoundClassifier {
        var callCount = 0
            private set

        override fun classify(file: File): String? {
            callCount++
            throwable?.let { throw it }
            return result
        }
    }

    @Test
    fun fehlgeschlagenerKlassifikatorVerhindertAufnahmeNicht() {
        // Simuliert eine komplett fehlgeschlagene Initialisierung (classifier == null, wie bei
        // NoiseClassifier nach einem im init-Block abgefangenen UnsatisfiedLinkError).
        val result = classifySafely(classifier = null, aiEnabled = true, file = file)

        assertNull(result)
    }

    @Test
    fun exceptionAusClassifyWirdNichtDurchgereicht() {
        // Der wichtigste Fall: classify() wirft (z.B. weil die Initialisierung zwar formal
        // geglueckt ist, der eigentliche Aufruf aber scheitert). Ohne den try/catch in
        // classifySafely() wuerde diese Exception bis zum Aufrufer in AudioRecordingService
        // durchschlagen und dort die WAV-Speicherung/den Datenbank-Insert verhindern.
        val throwingClassifier = FakeSoundClassifier(throwable = IllegalStateException("kaputt"))

        val result = classifySafely(classifier = throwingClassifier, aiEnabled = true, file = file)

        assertNull(result)
    }

    @Test
    fun aiDisabledRuftClassifierNichtAuf() {
        // Gegenprobe zu den beiden Faellen oben: classifySafely() soll den Klassifikator gar
        // nicht erst aufrufen, wenn die KI-Erkennung deaktiviert ist - nicht nur zufaellig immer
        // null liefern.
        val classifier = FakeSoundClassifier(result = "Hämmern")

        val result = classifySafely(classifier = classifier, aiEnabled = false, file = file)

        assertNull(result)
        assertEquals(0, classifier.callCount)
    }

    @Test
    fun funktionierenderKlassifikatorLiefertLabelWeiterhin() {
        // Gegenprobe zu den beiden fehlgeschlagenen Faellen: classifySafely() gibt kein
        // Blanko-null zurueck, sondern reicht ein tatsaechliches Ergebnis unveraendert durch -
        // sonst waeren die obigen "null"-Assertions nicht aussagekraeftig.
        val classifier = FakeSoundClassifier(result = "Hämmern")

        val result = classifySafely(classifier = classifier, aiEnabled = true, file = file)

        assertEquals("Hämmern", result)
        assertEquals(1, classifier.callCount)
    }
}
