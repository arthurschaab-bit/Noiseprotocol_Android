package com.example.lrmprotokoll.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChaquopyReportRunnerTest {
    @Test
    fun fachlicherValueErrorBleibtNutzerlesbar() {
        assertEquals(
            "Die Rohdaten-Datei fehlt.",
            pythonFehlerdetails("ValueError: Die Rohdaten-Datei fehlt."),
        )
    }

    @Test
    fun tracebackBewahrtPythonTypUndModulDesImportfehlers() {
        val details = pythonFehlerdetails(
            """
            Traceback (most recent call last):
              File "report_bridge.py", line 12, in <module>
            ModuleNotFoundError: No module named 'laermbericht.pdf_pages'
            """.trimIndent(),
        )

        assertEquals("ModuleNotFoundError: No module named 'laermbericht.pdf_pages'", details)
    }

    @Test
    fun importfehlerOhneTypErhaeltDiagnostischenPythonTyp() {
        val details = pythonFehlerdetails("No module named 'report_bridge'")

        assertTrue(details, details.startsWith("ModuleNotFoundError:"))
        assertTrue(details, details.contains("report_bridge"))
    }

    @Test
    fun chaquopyWrapperVerdecktPythonImporttypNicht() {
        val details = pythonFehlerdetails(
            "com.chaquo.python.PyException: ModuleNotFoundError: No module named 'report_bridge'",
        )

        assertEquals("ModuleNotFoundError: No module named 'report_bridge'", details)
    }
}
