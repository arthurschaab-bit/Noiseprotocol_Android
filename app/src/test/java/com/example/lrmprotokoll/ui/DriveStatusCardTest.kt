package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.data.DriveDailyFileEntity
import com.example.lrmprotokoll.data.DriveSyncState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DriveStatusCardTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun driveStatusCardZeigtNichtVerbundenZustand() {
        ApplicationProvider.getApplicationContext<LaermprotokollApp>()
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

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.drive_sync_title)).assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.drive_status_pill_disconnected)).assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.drive_connect_action)).assertIsDisplayed()

        composeRule.onNodeWithTag(DRIVE_CONNECT_BUTTON_TAG).performClick()
        assertTrue(connectClicked)
    }

    @Test
    fun driveStatusCardZeigtVerbundenZustandMitKontoUndSyncButton() {
        ApplicationProvider.getApplicationContext<LaermprotokollApp>()
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
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.drive_status_pill_active)).assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.drive_status_prefix, "120 Zeilen erfolgreich hochgeladen")).assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.drive_sync_now_action)).assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.drive_disconnect_action)).assertIsDisplayed()

        composeRule.onNodeWithTag(DRIVE_SYNC_NOW_BUTTON_TAG).performClick()
        assertTrue(syncNowClicked)

        composeRule.onNodeWithTag(DRIVE_DISCONNECT_BUTTON_TAG).performClick()
        assertTrue(disconnectClicked)
    }

    @Test
    fun driveStatusCardZeigtFehlerZustandBeiStoerung() {
        ApplicationProvider.getApplicationContext<LaermprotokollApp>()

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

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.drive_status_pill_error)).assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.drive_status_prefix, "Fehler: Ordner nicht gefunden")).assertIsDisplayed()
    }

    @Test
    fun driveStatusCardZeigtSyncingLadeanzeige() {
        ApplicationProvider.getApplicationContext<LaermprotokollApp>()

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

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.drive_uploading_progress)).assertIsDisplayed()
    }

    /**
     * Owner-Meldung 12.09.2026 ("Sicherung läuft sporadisch, nicht täglich"): eigene Zeile fuer
     * die Datenbank-Sicherung, getrennt vom allgemeinen Zeilen-/ZIP-Sync-Zeitstempel.
     */
    @Test
    fun driveStatusCardZeigtNochKeineSicherungWennAktivAberNieHochgeladen() {
        ApplicationProvider.getApplicationContext<LaermprotokollApp>()

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

        val expectedSicherung = composeRule.activity.getString(
            R.string.drive_last_db_backup,
            composeRule.activity.getString(R.string.drive_no_backup_yet)
        )
        composeRule.onNodeWithText(expectedSicherung).assertIsDisplayed()
    }

    @Test
    fun driveStatusCardZeigtKeineSicherungszeileWennDeaktiviert() {
        ApplicationProvider.getApplicationContext<LaermprotokollApp>()

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

        val backupPrefix = composeRule.activity.getString(R.string.drive_last_db_backup, "").split(":")[0]
        composeRule.onAllNodesWithText(backupPrefix, substring = true).assertCountEquals(0)
    }
}
