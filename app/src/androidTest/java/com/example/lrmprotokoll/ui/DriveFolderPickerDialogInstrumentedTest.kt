package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.lrmprotokoll.drive.DriveDatei
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * Echtes Geraete-Pendant zu [DriveFolderPickerDialogComposeTest] (Robolectric, app/src/test) -
 * Teil der Bestandsaufnahme nach dem Datumsbereich-Dialog-Bug (Owner-Meldung 15.09.2026): kein
 * Dialog/Popup im Projekt hatte durchgaengig echte Geraete-Abdeckung. Ruft den Dialog wie das
 * Robolectric-Pendant direkt mit Fake-Callbacks auf (statt ueber SettingsScreen/echtes Google-
 * Drive-Konto) - Sign-In/Netzwerk sind nicht Teil dieses Tests.
 */
@RunWith(AndroidJUnit4::class)
class DriveFolderPickerDialogInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun dialogZeigtOrdnerlisteUndErlaubtAuswahl() {
        val testFolders = listOf(
            DriveDatei("id-1", "Lärmprotokoll 2026"),
            DriveDatei("id-2", "Baustellen-Messungen")
        )
        var selectedFolder: DriveDatei? = null

        composeRule.setContent {
            DriveFolderPickerDialog(
                currentFolderId = "id-1",
                currentFolderName = "Lärmprotokoll 2026",
                onSelectFolder = { selectedFolder = it },
                onCreateFolder = { Result.success(DriveDatei("id-new", it)) },
                onRenameFolder = { _, _ -> Result.success(Unit) },
                onLoadFolders = { Result.success(testFolders) },
                onDismiss = {}
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Lärmprotokoll 2026").assertIsDisplayed()
        composeRule.onNodeWithText("Baustellen-Messungen").assertIsDisplayed()

        composeRule.onNodeWithText("Baustellen-Messungen").performClick()
        assertEquals("id-2", selectedFolder?.id)
        assertEquals("Baustellen-Messungen", selectedFolder?.name)
    }

    @Test
    fun neuerOrdnerErstellenTriggertCallbackUndSubDialogIstSichtbar() {
        val testFolders = listOf(DriveDatei("id-1", "Lärmprotokoll"))
        var createdName: String? = null
        var selectedFolder: DriveDatei? = null

        composeRule.setContent {
            DriveFolderPickerDialog(
                currentFolderId = "id-1",
                currentFolderName = "Lärmprotokoll",
                onSelectFolder = { selectedFolder = it },
                onCreateFolder = {
                    createdName = it
                    Result.success(DriveDatei("id-brand-new", it))
                },
                onRenameFolder = { _, _ -> Result.success(Unit) },
                onLoadFolders = { Result.success(testFolders) },
                onDismiss = {}
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(DRIVE_CREATE_FOLDER_BUTTON_TAG).assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        // Der Sub-Dialog liegt ueber dem Haupt-Dialog - auf einem echten Geraet muessen beide
        // Buttons wirklich sichtbar sein (siehe Regressionsfall im Datumsbereich-Dialog).
        composeRule.onNodeWithText("Neuen Ordner erstellen").assertIsDisplayed()
        composeRule.onNodeWithText("Erstellen & Auswählen").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        assertEquals("Lärmprotokoll", createdName)
        assertEquals("id-brand-new", selectedFolder?.id)
    }

    @Test
    fun refreshButtonLaedtOrdnerErneut() {
        var loadCount = 0
        composeRule.setContent {
            DriveFolderPickerDialog(
                currentFolderId = null,
                currentFolderName = "Lärmprotokoll",
                onSelectFolder = {},
                onCreateFolder = { Result.success(DriveDatei("id", it)) },
                onRenameFolder = { _, _ -> Result.success(Unit) },
                onLoadFolders = {
                    loadCount++
                    Result.success(emptyList())
                },
                onDismiss = {}
            )
        }
        composeRule.waitForIdle()

        val initialLoad = loadCount
        assertTrue("Initial sollte mindestens 1x geladen worden sein", initialLoad >= 1)

        composeRule.onNodeWithTag(DRIVE_REFRESH_FOLDERS_BUTTON_TAG).assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        assertTrue("Nach Klick auf Refresh sollte erneut geladen worden sein", loadCount > initialLoad)
    }
}
