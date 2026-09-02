package com.example.lrmprotokoll.audio

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * KI-Umbau Etappe 2.2: prueft JEDEN Eintrag der Gruppentabelle ([BAULAERM_KLASSEN]) UND jeden
 * Schluessel der bestehenden Deutsch-Mapping-Tabelle ([labelMapping]) gegen die tatsaechliche,
 * im Modell eingebettete Labelliste - nicht gegen Anhang A des Auftrags oder eine Annahme
 * (Arbeitsweise-Regel 2: "Rate nicht").
 *
 * `yamnet.tflite` traegt seine 521 Klassennamen als angehaengtes ZIP-Archiv
 * (`yamnet_label_list.txt`, TFLite-Metadaten-Konvention). `java.util.zip.ZipFile` findet dessen
 * "End of Central Directory"-Record vom Dateiende her und liest die Eintraege korrekt, obwohl
 * ihm ein Flatbuffer-Modellrumpf vorausgeht - dieselbe Technik, mit der z.B. selbstextrahierende
 * ZIPs oder Spring-Boot-Executable-JARs funktionieren, keine manuelle Offset-Berechnung noetig.
 */
class LabelMappingValidierungTest {

    companion object {
        private lateinit var modellLabels: Set<String>

        @BeforeClass
        @JvmStatic
        fun ladeModellLabels() {
            val datei = File("src/main/assets/yamnet.tflite")
            assertTrue("yamnet.tflite nicht gefunden unter ${datei.absolutePath}", datei.exists())
            ZipFile(datei).use { zip ->
                val eintrag = zip.getEntry("yamnet_label_list.txt")
                assertTrue("yamnet_label_list.txt nicht im Modell eingebettet", eintrag != null)
                modellLabels = zip.getInputStream(eintrag).bufferedReader().readLines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
            }
            assertTrue("Modell-Labelliste unplausibel klein (${modellLabels.size} Eintraege)", modellLabels.size > 100)
        }
    }

    @Test
    fun jedeBaulaermKlasseExistiertInDerModellLabelliste() {
        BAULAERM_KLASSEN.forEach { klasse ->
            assertTrue(
                "BAULAERM_KLASSEN: '${klasse.name}' (Index ${klasse.index}) existiert nicht in der " +
                    "tatsaechlichen Modell-Labelliste",
                klasse.name in modellLabels,
            )
        }
    }

    @Test
    fun jederLabelMappingSchluesselExistiertInDerModellLabelliste() {
        labelMapping.keys.forEach { schluessel ->
            assertTrue(
                "labelMapping: Schluessel '$schluessel' existiert nicht in der tatsaechlichen " +
                    "Modell-Labelliste - toter Eintrag, siehe Etappe-2.2-Befund im NoiseClassifier-KDoc",
                schluessel in modellLabels,
            )
        }
    }

    @Test
    fun keinBaulaermKlassenNameIstEinToterMappingEintragMehr() {
        // Gegenprobe zum Etappe-1-Befund: die urspruenglich toten Namen ("Hammering", "Excavator",
        // "Traffic noise" ohne Zusatz, ...) duerfen nach der Etappe-2.2-Korrektur nicht mehr als
        // labelMapping-Schluessel auftauchen.
        val tote = setOf(
            "Hammering", "Drilling", "Excavator", "Machinery", "Heavy machinery",
            "Construction", "Traffic noise", "Beep", "Saw",
        )
        tote.forEach { toterName ->
            assertTrue(
                "'$toterName' sollte nach der Etappe-2.2-Korrektur kein labelMapping-Schluessel mehr sein",
                toterName !in labelMapping.keys,
            )
        }
    }
}
