package com.example.lrmprotokoll.ui

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.util.ReflectionHelpers

/**
 * Testluecken-Auftrag Stufe 6: ersetzt den bisherigen reinen Render-Smoke-Test durch echte
 * Interaktionsketten - jeder der "Empfohlene Aktionen"-Buttons muss beim Klick genau den
 * richtigen System-Einstellungsdialog starten (sonst tippt der Nutzer ins Leere), und der
 * "Optimal konfiguriert"-Zustand darf keine Aktions-Buttons zeigen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OemDeviceHelperCardTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var context: LaermprotokollApp

    private fun konfiguriereAlsOptimal() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        shadowOf(powerManager).setIgnoringBatteryOptimizations(context.packageName, true)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @Test
    fun oemDeviceHelperCardWirdErfolgreichGerendert() {
        ApplicationProvider.getApplicationContext<LaermprotokollApp>()

        composeRule.setContent {
            OemDeviceHelperCard()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(OEM_HELPER_CARD_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.oem_card_title)).assertIsDisplayed()
    }

    @Test
    fun optimalerZustandZeigtKeineAktionsButtonsUndDenOptimalBadge() {
        konfiguriereAlsOptimal()

        composeRule.setContent { OemDeviceHelperCard() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(text(R.string.oem_badge_optimal)).assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.oem_action_disable_battery_optimization)).assertIsNotDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.oem_action_allow_notifications)).assertIsNotDisplayed()
    }

    @Test
    fun klickAufAkkuOptimierungAufhebenButtonStartetDenRichtigenSystemDialog() {
        konfiguriereAlsOptimal()
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        // Einziges verbleibendes Problem: Akku-Optimierung aktiv - so ist der Button eindeutig.
        shadowOf(powerManager).setIgnoringBatteryOptimizations(context.packageName, false)

        composeRule.setContent { OemDeviceHelperCard() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(text(R.string.oem_badge_check_needed)).assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.oem_action_disable_battery_optimization)).assertIsDisplayed().performClick()

        val gestarteteIntent = shadowOf(composeRule.activity).nextStartedActivity
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, gestarteteIntent.action)
        assertEquals("package:${context.packageName}", gestarteteIntent.data.toString())
    }

    @Test
    fun klickAufBenachrichtigungenErlaubenButtonStartetDenRichtigenSystemDialog() {
        konfiguriereAlsOptimal()
        // Einziges verbleibendes Problem: Benachrichtigungsberechtigung fehlt.
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        composeRule.setContent { OemDeviceHelperCard() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.oem_action_allow_notifications)).assertIsDisplayed().performClick()

        val gestarteteIntent = shadowOf(composeRule.activity).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, gestarteteIntent.action)
        assertEquals(context.packageName, gestarteteIntent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun klickAufExakteAlarmeFreischaltenButtonStartetDenRichtigenSystemDialog() {
        konfiguriereAlsOptimal()
        // Einziges verbleibendes Problem: exakte Alarme eingeschraenkt (nur ab Android 12/S relevant).
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        composeRule.setContent { OemDeviceHelperCard() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.oem_action_allow_exact_alarms)).assertIsDisplayed().performClick()

        val gestarteteIntent = shadowOf(composeRule.activity).nextStartedActivity
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, gestarteteIntent.action)
        assertEquals("package:${context.packageName}", gestarteteIntent.data.toString())
    }

    /**
     * Bugfix (Geraetetest Huawei P30 / ELE-L29, EMUI auf Android 10): dort existiert die Ziel-
     * Activity "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity" zwar, ist
     * aber nicht exportiert - startActivity() wirft dann eine SecurityException statt einer
     * ActivityNotFoundException. Der bisherige Code fing nur Letztere ab, der Klick auf
     * "Huawei / EMUI Geschützte Apps prüfen" crashte die App. Regressionstest: simuliert die
     * SecurityException und prueft, dass stattdessen der App-Detailseiten-Fallback startet.
     */
    private class SecurityExceptionWerfenderContext(base: Context) : ContextWrapper(base) {
        override fun startActivity(intent: Intent) {
            if (intent.component?.packageName == "com.huawei.systemmanager") {
                throw SecurityException("Permission Denial: not exported from uid 10123")
            }
            super.startActivity(intent)
        }
    }

    @Test
    fun klickAufHuaweiButtonBeiSecurityExceptionCrashtNichtSondernFaelltAufAppDetailsZurueck() {
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "HUAWEI")
        konfiguriereAlsOptimal()

        composeRule.setContent {
            CompositionLocalProvider(
                LocalContext provides SecurityExceptionWerfenderContext(LocalContext.current)
            ) {
                OemDeviceHelperCard()
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(text(R.string.oem_badge_check_needed)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.oem_autostart_huawei)).assertIsDisplayed().performClick()

        val gestarteteIntent = shadowOf(composeRule.activity).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, gestarteteIntent.action)
        assertEquals("package:${context.packageName}", gestarteteIntent.data.toString())
    }

    /**
     * F-20: der eigentliche Nachweis, dass diese Karte vollstaendig lokalisiert ist. Vor diesem
     * PR waren nur die drei Knopfbeschriftungen Ressourcen - in englischer Sprache stand ein
     * englischer Knopf unter einem deutschen Kartentitel.
     *
     * Die Sprachkennung in [Config.qualifiers] ist dasselbe Muster wie in
     * [CockpitKopfzeileTest], nur mit en statt de.
     *
     * **Review-Befund 01.10.2026:** eine erste Fassung hiess schon so, prueffte aber nur Titel und
     * Statusabzeichen - zwei von sieben sichtbaren Texten. Der Name behauptete damit mehr als der
     * Test hielt. Jetzt werden alle Texte geprueft, die der Optimalzustand rendert, und zusaetzlich
     * die Abwesenheit *aller* vormals harten deutschen Zeichenketten. Die deutschen Literale stehen
     * hier absichtlich hart im Test: genau sie sollen nicht mehr erscheinen, und ein
     * `getString` dafuer gaebe es unter der en-Kennung ohnehin nicht.
     */
    @Test
    @Config(sdk = [34], qualifiers = "en-rUS-w411dp-h891dp")
    fun inEnglischerSpracheStehtKeinDeutscherTextMehrInDerKarte() {
        konfiguriereAlsOptimal()

        composeRule.setContent { OemDeviceHelperCard() }
        composeRule.waitForIdle()

        // Alles, was der Optimalzustand zeigt - ueber getString geholt, nicht abgeschrieben.
        pruefeVorhanden(TEXTE_IM_OPTIMALZUSTAND)
        pruefeDassKeinDeutscherTextErscheint()
    }

    /**
     * Zweite Haelfte desselben Nachweises: der Optimalzustand versteckt die Aktionsliste samt
     * Ueberschrift und Knoepfen. Ohne diesen Test waeren fuenf der lokalisierten Schluessel in
     * keiner Sprachpruefung.
     */
    @Test
    @Config(sdk = [34], qualifiers = "en-rUS-w411dp-h891dp")
    fun inEnglischerSpracheGiltDasAuchFuerDieAktionsliste() {
        konfiguriereAlsOptimal()
        // Alle drei Probleme gleichzeitig, damit jeder Aktionsknopf erscheint.
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        shadowOf(powerManager).setIgnoringBatteryOptimizations(context.packageName, false)
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        composeRule.setContent { OemDeviceHelperCard() }
        composeRule.waitForIdle()

        pruefeVorhanden(TEXTE_IM_PROBLEMZUSTAND)
        pruefeDassKeinDeutscherTextErscheint()
    }

    /**
     * Zaehlt statt [assertIsDisplayed] zu rufen, und zwar aus zwei gemessenen Gruenden:
     * `oem_state_allowed` erscheint zweimal (Benachrichtigungen und exakte Alarme teilen den
     * Schluessel), da wirft `onNodeWithText` "Expected at most 1 node but found 2"; und die Karte
     * steht hier in keinem Scroll-Container, da wirft `performScrollTo` "no parent layout with a
     * Scroll SemanticsAction". Fuer eine Sprachpruefung ist das Vorhandensein im Semantikbaum
     * ohnehin die richtige Frage - die Sichtbarkeit pruefen die uebrigen Tests dieser Klasse.
     */
    private fun pruefeVorhanden(ids: List<Int>) {
        for (id in ids) {
            val erwartet = text(id)
            val knoten = composeRule.onAllNodesWithText(erwartet, substring = true)
            assertTrue(
                "Erwarteter englischer Text fehlt: \"$erwartet\"",
                knoten.fetchSemanticsNodes().isNotEmpty(),
            )
        }
    }

    private fun pruefeDassKeinDeutscherTextErscheint() {
        for (deutsch in VORMALS_HARTE_DEUTSCHE_TEXTE) {
            assertEquals(
                "In englischer Sprache darf \"$deutsch\" nicht erscheinen",
                0,
                composeRule.onAllNodesWithText(deutsch, substring = true).fetchSemanticsNodes().size,
            )
        }
    }

    /** Kuerzel fuer den lokalisierten Text - die Tests pruefen Verhalten, nicht Wortlaut. */
    private fun text(
        @StringRes id: Int,
    ): String = composeRule.activity.getString(id)

    private companion object {
        /** Was der Optimalzustand rendert: Titel, Abzeichen und die vier Befundzeilen. */
        val TEXTE_IM_OPTIMALZUSTAND =
            listOf(
                R.string.oem_card_title,
                R.string.oem_badge_optimal,
                R.string.oem_vibration_present,
                R.string.oem_state_allowed,
                R.string.oem_battery_exempt,
            )

        /** Was nur bei Problemen erscheint: Ueberschrift, die drei Knoepfe, die Problemzustaende. */
        val TEXTE_IM_PROBLEMZUSTAND =
            listOf(
                R.string.oem_badge_check_needed,
                R.string.oem_recommended_actions,
                R.string.oem_action_allow_notifications,
                R.string.oem_action_disable_battery_optimization,
                R.string.oem_action_allow_exact_alarms,
                R.string.oem_notifications_blocked,
                R.string.oem_exact_alarms_restricted,
                R.string.oem_battery_restricted,
            )

        /**
         * Genau die Zeichenketten, die vor diesem PR hart im Quelltext von [OemDeviceHelperCard]
         * standen. Bewusst als Literale: sie sind der Altzustand, den dieser PR beseitigt, und
         * duerfen in keiner anderen Sprache mehr auftauchen. Die Praefixe mit Doppelpunkt genuegen,
         * weil die Befundzeilen Formatzeichenketten sind.
         */
        val VORMALS_HARTE_DEUTSCHE_TEXTE =
            listOf(
                "Geräte- & Alarm-Diagnose",
                "Prüfung nötig",
                "Optimal konfiguriert",
                "Modell:",
                "• Hardware-Vibration:",
                "• Benachrichtigungen:",
                "• Exakte Alarme:",
                "• Akku-Optimierung:",
                "Empfohlene Aktionen für zuverlässige Alarme:",
            )
    }
}
