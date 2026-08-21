package com.example.lrmprotokoll.ui

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.R
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * JVM / Robolectric-Test zur Sicherstellung der Ressourcen- und Icon-Integrität.
 *
 * Verhindert, dass unvollständige XMLs oder fehlende String-/Drawable-Ressourcen
 * unbemerkt zur Laufzeit zu `Resources.NotFoundException` führen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResourceIntegrityTest {

    @Test
    fun alleWichtigenAppRessourcenExistierenUndLassenSichLaden() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // 1. Strings prüfen
        assertTrue(context.getString(R.string.app_name).isNotEmpty())
        assertTrue(context.getString(R.string.nav_start).isNotEmpty())
        assertTrue(context.getString(R.string.nav_meter).isNotEmpty())
        assertTrue(context.getString(R.string.nav_protocol).isNotEmpty())
        assertTrue(context.getString(R.string.nav_diagnose).isNotEmpty())
        assertTrue(context.getString(R.string.nav_settings).isNotEmpty())
        assertTrue(context.getString(R.string.nav_trash).isNotEmpty())
        assertTrue(context.getString(R.string.empty_protocol_desc).isNotEmpty())

        // 2. Drawables prüfen
        val qsIcon = ContextCompat.getDrawable(context, R.drawable.ic_qs_noise)
        assertNotNull("ic_qs_noise Drawable muss existieren", qsIcon)
    }
}
