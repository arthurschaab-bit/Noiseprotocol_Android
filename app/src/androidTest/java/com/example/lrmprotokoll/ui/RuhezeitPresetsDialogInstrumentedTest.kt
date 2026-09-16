package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checkliste Button/Screen-Coverage (Phase 1b): [RuhezeitPresetsDialog] hatte bislang ueberhaupt
 * keinen Test - weder die Preset-Auswahl noch die "Auch Tagesschwelle uebernehmen"-Checkbox
 * waren je geprueft.
 */
@RunWith(AndroidJUnit4::class)
class RuhezeitPresetsDialogInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun zeigtAlleWohnraumPresetsUndAktivenZustandFuerDieAktuelleSchwelle() {
        composeRule.setContent {
            RuhezeitPresetsDialog(
                aktuelleNachtSchwelle = 40f,
                onDismissRequest = {},
                onPresetSelected = { _, _ -> },
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Grenzwerte nach Wohnraum").assertIsDisplayed()
        WOHNRAUM_PRESETS.forEach { preset ->
            composeRule.onNodeWithText(preset.titel).assertIsDisplayed()
        }
        // WA-Preset hat nachtGrenzwertDb = 40f, muss als aktiv markiert sein.
        composeRule.onNodeWithContentDescription("Aktiv").assertIsDisplayed()
    }

    @Test
    fun klickAufPresetOhneCheckboxUebergibtNurDieNachtschwelle() {
        var uebergebeneNacht: Float? = null
        var uebergebenerTag: Float? = null
        var dismissed = false

        composeRule.setContent {
            RuhezeitPresetsDialog(
                aktuelleNachtSchwelle = 40f,
                onDismissRequest = { dismissed = true },
                onPresetSelected = { nacht, tag ->
                    uebergebeneNacht = nacht
                    uebergebenerTag = tag
                },
            )
        }
        composeRule.waitForIdle()

        val wrPreset = WOHNRAUM_PRESETS.first { it.id == "WR" }
        composeRule.onNodeWithText(wrPreset.titel).performClick()

        assertEquals(wrPreset.nachtGrenzwertDb, uebergebeneNacht)
        assertNull("Ohne aktivierte Checkbox darf kein Tageswert uebergeben werden", uebergebenerTag)
        assertTrue("Klick auf ein Preset muss den Dialog schliessen", dismissed)
    }

    @Test
    fun checkboxAktiviertUebernahmeDerTagesschwelleBeimPresetKlick() {
        var uebergebenerTag: Float? = null

        composeRule.setContent {
            RuhezeitPresetsDialog(
                aktuelleNachtSchwelle = 40f,
                onDismissRequest = {},
                onPresetSelected = { _, tag -> uebergebenerTag = tag },
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Auch reguläre Tagesschwelle anpassen").performClick()

        val miPreset = WOHNRAUM_PRESETS.first { it.id == "MI" }
        composeRule.onNodeWithText(miPreset.titel).performClick()

        assertEquals(miPreset.tagGrenzwertDb, uebergebenerTag)
    }

    @Test
    fun schliessenButtonRuftOnDismissRequestAufOhneAuswahl() {
        var dismissed = false
        var ausgewaehlt = false

        composeRule.setContent {
            RuhezeitPresetsDialog(
                aktuelleNachtSchwelle = 40f,
                onDismissRequest = { dismissed = true },
                onPresetSelected = { _, _ -> ausgewaehlt = true },
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Schließen").performClick()

        assertTrue(dismissed)
        assertTrue("Schliessen darf kein Preset auswaehlen", !ausgewaehlt)
    }
}
