package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.data.DiagnosticLogEntity
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * M12 Schritt 7 Gegenprobe (Konzept Abschnitt 2): bei mehr Diagnose-Log-Eintraegen als der
 * Grenze wird nur die Grenze dargestellt, nicht die komplette Tabelle - genau das war die
 * Absturzursache-Vermutung (`DiagnosticLogDao.alle()` ohne `LIMIT`). "Weitere laden" erhoeht die
 * Grenze und laedt mehr nach.
 *
 * Basiszeitstempel weit in der Zukunft und deutlich MEHR eigene Eintraege (450) als beide hier
 * geprueften Grenzen (200 und 400) zusammen: die Datenbank ist nicht in-memory-isoliert je
 * Testmethode (siehe DiagnosticLogDaoTest) - mit weniger eigenen Eintraegen als der zweiten
 * Grenze koennten Zeilen aus anderen, in derselben JVM-Fork zuvor gelaufenen Tests in die
 * "neueste 400"-Anzeige hineinrutschen und die exakte Kopfzeilen-Zahl verfaelschen. Mit 450
 * eigenen (allesamt juenger datierten) Zeilen ist die Anzeige bei beiden Grenzen ausschliesslich
 * aus dieser Testmethode gespeist, unabhaengig von der Ausfuehrungsreihenfolge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiagnoseLogPaginierungTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun beiMehrEintraegenAlsDerGrenzeWirdNurDieGrenzeDargestelltUndWeitereLadenErgaenzt() {
        val container = ApplicationProvider.getApplicationContext<LaermprotokollApp>().container
        val basis = 3_300_000_000_000L
        runBlocking {
            (1..450).forEach { i ->
                container.database.diagnosticLogDao().insert(
                    DiagnosticLogEntity(timestamp = basis + i, message = "Testeintrag $i")
                )
            }
        }

        composeRule.setContent { DiagnoseScreen(onBack = {}) }
        composeRule.waitForIdle()

        val headerBeiStartgrenze = composeRule.activity.getString(com.example.lrmprotokoll.R.string.diagnose_log_header, 200)
        val ladeKnopf = composeRule.activity.getString(com.example.lrmprotokoll.R.string.diagnose_log_load_more)

        // CI-Fund (23.09.2026, PR #187): Kopfzeile (diagnoseLog.size) und Knopf haengen an der
        // Room-Emission - erst warten, dann scrollen, siehe scrolleZuSobaldGeladen.
        composeRule.scrolleZuSobaldGeladen(DIAGNOSE_LAZY_COLUMN_TAG, hasText(headerBeiStartgrenze))
        composeRule.onNodeWithText(headerBeiStartgrenze).assertExists()

        composeRule.scrolleZuSobaldGeladen(DIAGNOSE_LAZY_COLUMN_TAG, hasText(ladeKnopf))
        composeRule.onNodeWithText(ladeKnopf).performClick()
        composeRule.waitForIdle()

        val headerNachWeitereLaden = composeRule.activity.getString(com.example.lrmprotokoll.R.string.diagnose_log_header, 400)
        composeRule.scrolleZuSobaldGeladen(DIAGNOSE_LAZY_COLUMN_TAG, hasText(headerNachWeitereLaden))
        composeRule.onNodeWithText(headerNachWeitereLaden).assertExists()
    }
}
