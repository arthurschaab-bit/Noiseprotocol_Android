package com.example.lrmprotokoll.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.example.lrmprotokoll.drive.DriveDatei
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DriveFolderPickerDialogComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

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

        // 1. Beide Ordner werden angezeigt
        composeRule.onNodeWithText("Lärmprotokoll 2026").assertIsDisplayed()
        composeRule.onNodeWithText("Baustellen-Messungen").assertIsDisplayed()

        // 2. Klick auf den zweiten Ordner wählt ihn aus
        composeRule.onNodeWithText("Baustellen-Messungen").performClick()
        assertEquals("id-2", selectedFolder?.id)
        assertEquals("Baustellen-Messungen", selectedFolder?.name)
    }

    @Test
    fun neuerOrdnerErstellenTriggertCallback() {
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

        // Klick auf "Neuer Ordner"
        composeRule.onNodeWithTag(DRIVE_CREATE_FOLDER_BUTTON_TAG).performClick()
        composeRule.waitForIdle()

        // Sub-Dialog sichtbar
        composeRule.onNodeWithText("Neuen Ordner erstellen").assertIsDisplayed()
        composeRule.onNodeWithText("Erstellen & Auswählen").performClick()
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

        composeRule.onNodeWithTag(DRIVE_REFRESH_FOLDERS_BUTTON_TAG).performClick()
        composeRule.waitForIdle()

        assertTrue("Nach Klick auf Refresh sollte erneut geladen worden sein", loadCount > initialLoad)
    }
}
