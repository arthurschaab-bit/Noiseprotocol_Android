package com.example.lrmprotokoll.service

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.R
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentierter Test für den Quick-Settings-Tile Service [NoiseMonitoringTileService].
 *
 * Verifiziert:
 * 1. Die Manifest-Registrierung mit Berechtigung `BIND_QUICK_SETTINGS_TILE`.
 * 2. Das Vorhandensein und Laden des Tile-Icons `@drawable/ic_qs_noise` zur Vermeidung von System-Crashes.
 */
@RunWith(AndroidJUnit4::class)
class TileServiceInstrumentedTest {

    @Test
    fun tileServiceIstImManifestRegistriertUndIconLaesstSichLaden() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val packageManager = context.packageManager

        val componentName = ComponentName(context, NoiseMonitoringTileService::class.java)
        val serviceInfo = packageManager.getServiceInfo(componentName, PackageManager.GET_META_DATA)

        assertNotNull("NoiseMonitoringTileService muss im Manifest deklariert sein", serviceInfo)
        assertTrue("NoiseMonitoringTileService muss exportiert sein", serviceInfo.exported)
        assertTrue("Permission muss BIND_QUICK_SETTINGS_TILE sein", serviceInfo.permission == "android.permission.BIND_QUICK_SETTINGS_TILE")

        // Prüfe, dass das Icon als Drawable geladen werden kann
        val iconDrawable = ContextCompat.getDrawable(context, R.drawable.ic_qs_noise)
        assertNotNull("Das Tile-Icon ic_qs_noise muss geladen werden können", iconDrawable)
    }
}
