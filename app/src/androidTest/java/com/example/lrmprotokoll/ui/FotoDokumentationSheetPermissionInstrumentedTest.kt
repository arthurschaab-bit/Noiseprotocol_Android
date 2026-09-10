package com.example.lrmprotokoll.ui

import android.Manifest
import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.BerechtigungsTestHelfer
import com.example.lrmprotokoll.LaermprotokollApp
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regressionstest fuer #129: der Aufnahme-Button in [FotoDokumentationSheet] wirkte "ohne
 * Funktion", weil `android.permission.CAMERA` im Manifest deklariert, aber zur Laufzeit nicht
 * gewaehrt war - die App hat das nie geprueft, sondern den Kamera-Intent einfach abgeschickt.
 * Testtiefe/Grenzen: siehe KDoc von [BerechtigungsTestHelfer].
 *
 * Die Kamera-Antwort wird ueber Espresso-Intents gestubbt ([Intents.intending]), statt auf eine
 * echte Kamera-App im Emulator zu setzen (der CI-Emulator laeuft mit `-camera-back none`, siehe
 * `emulator-tests.yml`) - so pruefen beide Tests ausschliesslich, DASS unser Code den
 * `ACTION_IMAGE_CAPTURE`-Intent an der richtigen Stelle abschickt, unabhaengig davon, ob das
 * jeweilige Emulator-Image ueberhaupt eine Kamera-App mitbringt.
 */
@RunWith(AndroidJUnit4::class)
class FotoDokumentationSheetPermissionInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: LaermprotokollApp

    // Eindeutig je Testlauf: die Datenbank ist nicht je Testmethode isoliert (dieselbe Konvention
    // wie in den JVM-Tests, siehe PeriodenBerichtDatenTest), eine feste ID koennte mit anderen
    // Tests kollidieren.
    private val sessionId = System.currentTimeMillis()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        Intents.init()
        Intents.intending(hasAction(MediaStore.ACTION_IMAGE_CAPTURE))
            .respondWith(ActivityResult(Activity.RESULT_CANCELED, null))
    }

    @After
    fun tearDown() {
        Intents.release()
        // Sauberer Ausgangszustand fuer andere Tests in derselben Instrumentierungs-Session.
        BerechtigungsTestHelfer.gewaehre(Manifest.permission.CAMERA)
    }

    @Test
    fun ohneBerechtigungFragtDerAufnahmeButtonErstNachUndStartetDannDenKameraIntent() {
        BerechtigungsTestHelfer.entziehe(Manifest.permission.CAMERA)
        composeRule.setContent { FotoDokumentationSheet(sessionId = sessionId, onFertig = {}) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("foto_aufnahme_MESSAUFBAU").performClick()
        // Der eigentliche #129-Nachweis: Ohne dieses Antippen des ECHTEN Systemdialogs kommt der
        // Test nie hierher - lief die App wie vor dem Fix einfach weiter, ohne zu fragen, wuerde
        // kein Dialog erscheinen und dieser Aufruf mit einem AssertionError scheitern.
        BerechtigungsTestHelfer.erlaubeSystemdialog()
        composeRule.waitForIdle()

        Intents.intended(hasAction(MediaStore.ACTION_IMAGE_CAPTURE))
    }

    @Test
    fun mitBerechtigungStartetDerAufnahmeButtonSofortOhneUmwegDenKameraIntent() {
        BerechtigungsTestHelfer.gewaehre(Manifest.permission.CAMERA)
        composeRule.setContent { FotoDokumentationSheet(sessionId = sessionId, onFertig = {}) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("foto_aufnahme_MESSAUFBAU").performClick()
        composeRule.waitForIdle()

        Intents.intended(hasAction(MediaStore.ACTION_IMAGE_CAPTURE))
    }
}
