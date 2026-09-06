package com.example.lrmprotokoll.report

import android.content.ContextWrapper
import android.content.Intent
import android.content.ActivityNotFoundException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BeweisVideoOeffnenTest {
    private val appContext = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val started = mutableListOf<Intent>()
    private val context = object : ContextWrapper(appContext) {
        override fun startActivity(intent: Intent) { started.add(intent) }
    }

    @Test
    fun videoWirdMitLesbarerContentUriUndVideoTypGeoeffnet() {
        val file = File(context.getExternalFilesDir(null), "video mit Leerzeichen.mp4")
        file.writeBytes(byteArrayOf(1, 2, 3))
        try {
            assertTrue(BerichtDatei.oeffne(context, file))
            val intent = started.single()
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("video/mp4", intent.type)
            assertEquals("content", intent.data!!.scheme)
            assertEquals("${context.packageName}.fileprovider", intent.data!!.authority)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
            context.contentResolver.openInputStream(intent.data!!)!!.use {
                assertArrayEquals(file.readBytes(), it.readBytes())
            }
            // Rueckkehr aus dem externen Player: erneutes Oeffnen braucht keinen UI-Player-State.
            assertTrue(BerichtDatei.oeffne(context, file))
            assertEquals(2, started.size)
        } finally { file.delete() }
    }

    @Test
    fun fehlendeOderNichtFreigegebeneDateiCrashtNicht() {
        assertFalse(BerichtDatei.oeffne(context, File(context.cacheDir, "fehlt.mp4")))
        val privateFile = File(context.filesDir, "privat.mp4")
        privateFile.writeText("video")
        try { assertFalse(BerichtDatei.oeffne(context, privateFile)) }
        finally { privateFile.delete() }
    }

    @Test
    fun fehlenderPlayerWirdAlsFehlerZurueckgegeben() {
        val file = File(context.cacheDir, "video.mp4").apply { writeText("video") }
        var attempted = false
        val noPlayer = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) {
                attempted = true
                throw ActivityNotFoundException()
            }
        }
        try {
            assertFalse(BerichtDatei.oeffne(noPlayer, file))
            assertTrue(attempted)
        }
        finally { file.delete() }
    }
}


