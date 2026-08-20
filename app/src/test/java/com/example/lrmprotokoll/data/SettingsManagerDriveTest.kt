package com.example.lrmprotokoll.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Owner-Rückmeldung nach dem Gerätetest: "WAV ebenfalls hochladen sollte default an sein." -
 * kehrt die frühere Owner-Entscheidung aus Plan 13, Punkt 7 ("als Option vorhanden, Default AUS")
 * um. Eigene, kleine Testklasse statt Erweiterung von [SettingsManagerSecureTest]: dort geht es
 * um die verschlüsselte Ablage (ntfy/Heartbeat), `driveUploadWav` liegt im normalen Klartext-
 * SharedPreferences und hat damit keine Berührung mit jener Migrations-/Fallback-Logik.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsManagerDriveTest {

    @Test
    fun wavUploadIstStandardmaessigAn() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = SettingsManager(context)

        assertTrue(settings.driveUploadWav)
    }

    @Test
    fun wavUploadBleibtAbschaltbarUndUeberlebtEineNeueInstanz() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        SettingsManager(context).driveUploadWav = false

        assertEquals(false, SettingsManager(context).driveUploadWav)
    }
}
