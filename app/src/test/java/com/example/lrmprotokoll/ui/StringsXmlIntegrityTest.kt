package com.example.lrmprotokoll.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * JVM-Test zur Absicherung der Lokalisierung (F-20).
 *
 * Vergleicht die drei Resource-Dateien:
 * - values/strings.xml (Standard, Deutsch)
 * - values-de/strings.xml (Deutsch)
 * - values-en/strings.xml (Englisch)
 *
 * Stellt sicher:
 * 1. Alle drei Dateien haben exakt dieselbe Schlüsselmenge.
 * 2. Kein Wert ist leer.
 * 3. Mindestanzahl von Schlüsseln (Baseline 509).
 */
class StringsXmlIntegrityTest {
    private fun resolveResourceFile(
        folder: String,
        name: String = "strings.xml",
    ): File {
        val candidates =
            listOf(
                File("src/main/res/$folder/$name"),
                File("app/src/main/res/$folder/$name"),
            )
        return candidates.firstOrNull { it.exists() }
            ?: throw IllegalStateException("$name fuer $folder nicht gefunden an: $candidates")
    }

    private fun parseStringsXml(file: File): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(file)
        doc.documentElement.normalize()

        val stringNodes = doc.getElementsByTagName("string")
        val map = mutableMapOf<String, String>()

        for (i in 0 until stringNodes.length) {
            val element = stringNodes.item(i) as Element
            val name = element.getAttribute("name")
            val text = element.textContent
            map[name] = text
        }
        return map
    }

    @Test
    fun alleDreiStringsXmlDateienHabenExaktGleicheSchluesselmengenUndKeineLeerenWerte() {
        val fileDefault = resolveResourceFile("values")
        val fileDe = resolveResourceFile("values-de")
        val fileEn = resolveResourceFile("values-en")

        val stringsDefault = parseStringsXml(fileDefault)
        val stringsDe = parseStringsXml(fileDe)
        val stringsEn = parseStringsXml(fileEn)

        assertTrue(
            "Baseline muss mindestens 509 Eintraege haben (gefunden: ${stringsDefault.size})",
            stringsDefault.size >= 509,
        )

        // 1. Keine leeren Werte
        for ((key, value) in stringsDefault) {
            assertTrue("Wert fuer '$key' in values/ darf nicht leer sein", value.trim().isNotEmpty())
        }
        for ((key, value) in stringsDe) {
            assertTrue("Wert fuer '$key' in values-de/ darf nicht leer sein", value.trim().isNotEmpty())
        }
        for ((key, value) in stringsEn) {
            assertTrue("Wert fuer '$key' in values-en/ darf nicht leer sein", value.trim().isNotEmpty())
        }

        // 2. Schlüsselmengen vergleichen
        val keysDefault = stringsDefault.keys
        val keysDe = stringsDe.keys
        val keysEn = stringsEn.keys

        val missingInDe = keysDefault - keysDe
        val extraInDe = keysDe - keysDefault
        val missingInEn = keysDefault - keysEn
        val extraInEn = keysEn - keysDefault

        assertTrue(
            "Fehlt in values-de: $missingInDe, Zuviel in values-de: $extraInDe",
            missingInDe.isEmpty() && extraInDe.isEmpty(),
        )
        assertTrue(
            "Fehlt in values-en: $missingInEn, Zuviel in values-en: $extraInEn",
            missingInEn.isEmpty() && extraInEn.isEmpty(),
        )

        assertEquals("Anzahl in values vs values-de", keysDefault.size, keysDe.size)
        assertEquals("Anzahl in values vs values-en", keysDefault.size, keysEn.size)
    }

    @Test
    fun f20RestRessourcenSindInAllenDreiSprachenVollstaendig() {
        val name = "ux_phase7_f20_rest.xml"
        val dateien = listOf("values", "values-de", "values-en")
        val schluessel =
            dateien.map { ordner ->
                val dokument =
                    DocumentBuilderFactory
                        .newInstance()
                        .newDocumentBuilder()
                        .parse(resolveResourceFile(ordner, name))
                val elemente = dokument.documentElement.childNodes
                buildSet {
                    for (i in 0 until elemente.length) {
                        val element = elemente.item(i) as? Element ?: continue
                        val schluesselName = element.getAttribute("name")
                        assertTrue("Leerer Name in $ordner/$name", schluesselName.isNotBlank())
                        assertTrue("Leerer Wert fuer $schluesselName in $ordner/$name", element.textContent.isNotBlank())
                        assertTrue("Doppelter Name $schluesselName in $ordner/$name", add(schluesselName))
                    }
                }
            }
        assertTrue("F-20 Rest muss Ressourcen enthalten", schluessel.first().isNotEmpty())
        assertEquals("values-de hat andere Schluessel", schluessel[0], schluessel[1])
        assertEquals("values-en hat andere Schluessel", schluessel[0], schluessel[2])
    }
}
