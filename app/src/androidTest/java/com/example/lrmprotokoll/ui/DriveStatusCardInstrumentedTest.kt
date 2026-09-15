package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.data.DriveDailyFileEntity
import com.example.lrmprotokoll.data.DriveSyncState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Echtes Geraete-Pendant zu [DriveStatusCardTest] (Robolectric, app/src/test) - Teil der
 * Bestandsaufnahme nach dem Datumsbereich-Dialog-Bug (Owner-Auftrag 15.09.2026). Reiner,
 * isolierter Komponententest ohne Dialog/Fenster-Kontext.
 */
@RunWith(AndroidJUnit4::class)
class DriveStatusCardInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun driveStatusCardZeigtNichtVerbundenZustand() {
        var connectClicked = false

        composeRule.setContent {
            DriveStatusCard(
                googleAccountEmail = null,
                googleAccountName = null,
                syncEnabled = false,
                folderName = "Lärmprotokoll",
                folderId = null,
                isFolderBlocked = false,
                consecutiveFailures = 0,
                lastSuccessAt = 0L,
                lastMessage = null,
                latestDailyFile = null,
                isSyncing = false,
                onToggleSync = {},
                onSyncNow = {},
                onConnectGoogle = { connectClicked = true },
                onDisconnectGoogle = {},
                onUpdateFolderName = {}
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Google Drive Sync").assertIsDisplayed()
        composeRule.onNodeWithText("Nicht verbunden").assertIsDisplayed()
        composeRule.onNodeWithText("Mit Google Drive verbinden").assertIsDisplayed()

        composeRule.onNodeWithTag(DRIVE_CONNECT_BUTTON_TAG).performClick()
        assertTrue(connectClicked)
    }

    @Test
    fun driveStatusCardZeigtVerbundenZustandMitKontoUndSyncButton() {
        var syncNowClicked = false
        var disconnectClicked = false

        val dummyDailyFile = DriveDailyFileEntity(
            date = "2026-08-22",
            fileId = "test_file_id_12345",
            lastSyncedAt = 1787376000000L,
            lastRowCount = 120,
            state = DriveSyncState.SYNCED
        )

        composeRule.setContent {
            DriveStatusCard(
                googleAccountEmail = "tester@gmail.com",
                googleAccountName = "Arthur Tester",
                syncEnabled = true,
                folderName = "Lärmprotokoll",
                folderId = "folder_abc_123",
                isFolderBlocked = false,
                consecutiveFailures = 0,
                lastSuccessAt = 1787376000000L,
                lastMessage = "120 Zeilen erfolgreich hochgeladen",
                latestDailyFile = dummyDailyFile,
                isSyncing = false,
                onToggleSync = {},
                onSyncNow = { syncNowClicked = true },
                onConnectGoogle = {},
                onDisconnectGoogle = { disconnectClicked = true },
                onUpdateFolderName = {}
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Arthur Tester (tester@gmail.com)").assertIsDisplayed()
        composeRule.onNodeWithText("Aktiv").assertIsDisplayed()
        composeRule.onNodeWithText("Status: 120 Zeilen erfolgreich hochgeladen").assertIsDisplayed()
        composeRule.onNodeWithText("Jetzt synchronisieren").assertIsDisplayed()
        composeRule.onNodeWithText("Trennen").assertIsDisplayed()

        composeRule.onNodeWithTag(DRIVE_SYNC_NOW_BUTTON_TAG).performClick()
        assertTrue(syncNowClicked)

        composeRule.onNodeWithTag(DRIVE_DISCONNECT_BUTTON_TAG).performClick()
        assertTrue(disconnectClicked)
    }

    @Test
    fun driveStatusCardZeigtFehlerZustandBeiStoerung() {
        composeRule.setContent {
            DriveStatusCard(
                googleAccountEmail = "tester@gmail.com",
                googleAccountName = null,
                syncEnabled = true,
                folderName = "Lärmprotokoll",
                folderId = "folder_abc_123",
                isFolderBlocked = true,
                consecutiveFailures = 3,
                lastSuccessAt = 0L,
                lastMessage = "Fehler: Ordner nicht gefunden",
                latestDailyFile = null,
                isSyncing = false,
                onToggleSync = {},
                onSyncNow = {},
                onConnectGoogle = {},
                onDisconnectGoogle = {},
                onUpdateFolderName = {}
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Gestört").assertIsDisplayed()
        composeRule.onNodeWithText("Status: Fehler: Ordner nicht gefunden").assertIsDisplayed()
    }

    @Test
    fun driveStatusCardZeigtSyncingLadeanzeige() {
        composeRule.setContent {
            DriveStatusCard(
                googleAccountEmail = "tester@gmail.com",
                googleAccountName = null,
                syncEnabled = true,
                folderName = "Lärmprotokoll",
                folderId = "folder_abc_123",
                isFolderBlocked = false,
                consecutiveFailures = 0,
                lastSuccessAt = 1000L,
                lastMessage = null,
                latestDailyFile = null,
                isSyncing = true,
                onToggleSync = {},
                onSyncNow = {},
                onConnectGoogle = {},
                onDisconnectGoogle = {},
                onUpdateFolderName = {}
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Wird hochgeladen…").assertIsDisplayed()
    }

    /**
     * Owner-Meldung 12.09.2026 ("Sicherung läuft sporadisch, nicht täglich"): eigene Zeile fuer
     * die Datenbank-Sicherung, getrennt vom allgemeinen Zeilen-/ZIP-Sync-Zeitstempel.
     */
    @Test
    fun driveStatusCardZeigtNochKeineSicherungWennAktivAberNieHochgeladen() {
        composeRule.setContent {
            DriveStatusCard(
                googleAccountEmail = "tester@gmail.com",
                googleAccountName = null,
                syncEnabled = true,
                folderName = "Lärmprotokoll",
                folderId = "folder_abc_123",
                isFolderBlocked = false,
                consecutiveFailures = 0,
                lastSuccessAt = 1787376000000L,
                lastMessage = null,
                latestDailyFile = null,
                datenbankSicherungAktiv = true,
                datenbankSicherungLastSuccessAt = 0L,
                isSyncing = false,
                onToggleSync = {},
                onSyncNow = {},
                onConnectGoogle = {},
                onDisconnectGoogle = {},
                onUpdateFolderName = {}
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Letzte Datenbank-Sicherung: Noch keine Sicherung hochgeladen").assertIsDisplayed()
    }

    @Test
    fun driveStatusCardZeigtKeineSicherungszeileWennDeaktiviert() {
        composeRule.setContent {
            DriveStatusCard(
                googleAccountEmail = "tester@gmail.com",
                googleAccountName = null,
                syncEnabled = true,
                folderName = "Lärmprotokoll",
                folderId = "folder_abc_123",
                isFolderBlocked = false,
                consecutiveFailures = 0,
                lastSuccessAt = 1787376000000L,
                lastMessage = null,
                latestDailyFile = null,
                datenbankSicherungAktiv = false,
                isSyncing = false,
                onToggleSync = {},
                onSyncNow = {},
                onConnectGoogle = {},
                onDisconnectGoogle = {},
                onUpdateFolderName = {}
            )
        }
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText("Letzte Datenbank-Sicherung", substring = true).assertCountEquals(0)
    }
}
