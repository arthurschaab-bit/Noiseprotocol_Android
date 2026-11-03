package com.example.lrmprotokoll.diagnose

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.AppContainer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit-Tests für Schritt 7 aus `docs/PROMPT_DIAGNOSE_STILLSTAND.md`:
 * Erkennung von Hintergrundbeschränkungen (direkt über Systemsignale und indirekt über die
 * Wächter-Spur nach einem Ausfall) sowie Fallback-Kette für den Einstellungs-Sprung.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HintergrundbeschraenkungHinweisTest {
    @Test
    fun allesUnbeschraenktOhneAusfallLiefertKeinenHinweis() {
        val befund =
            HintergrundbeschraenkungLogik.bewerten(
                signale =
                    HintergrundbeschraenkungSignale(
                        hintergrundEingeschraenkt = false,
                        standbyBucket = 10,
                        batterieoptimierungIgnoriert = true,
                    ),
                letzterAusfallKlasse = null,
                letzterAusfallWaechterLief = null,
            )
        assertFalse(befund.aktiv)
        assertTrue(befund.gruende.isEmpty())
        assertFalse(befund.zeigtHuaweiAppStartAnleitung)
    }

    @Test
    fun isBackgroundRestrictedWirdErkannt() {
        val befund =
            HintergrundbeschraenkungLogik.bewerten(
                signale =
                    HintergrundbeschraenkungSignale(
                        hintergrundEingeschraenkt = true,
                        standbyBucket = 10,
                        batterieoptimierungIgnoriert = true,
                    ),
                letzterAusfallKlasse = null,
                letzterAusfallWaechterLief = null,
            )
        assertTrue(befund.aktiv)
        assertTrue(HintergrundbeschraenkungGrund.HINTERGRUND_EINGESCHRAENKT in befund.gruende)
    }

    @Test
    fun standbyBucketRareUndRestrictedWerdenErkannt() {
        val rareBefund =
            HintergrundbeschraenkungLogik.bewerten(
                signale =
                    HintergrundbeschraenkungSignale(
                        hintergrundEingeschraenkt = false,
                        standbyBucket = HintergrundbeschraenkungLogik.STANDBY_BUCKET_RARE,
                        batterieoptimierungIgnoriert = true,
                    ),
                letzterAusfallKlasse = null,
                letzterAusfallWaechterLief = null,
            )
        assertTrue(rareBefund.aktiv)
        assertTrue(HintergrundbeschraenkungGrund.STANDBY_BUCKET_EINGESCHRAENKT in rareBefund.gruende)
        assertEquals(40, rareBefund.standbyBucket)

        val restrictedBefund =
            HintergrundbeschraenkungLogik.bewerten(
                signale =
                    HintergrundbeschraenkungSignale(
                        hintergrundEingeschraenkt = false,
                        standbyBucket = HintergrundbeschraenkungLogik.STANDBY_BUCKET_RESTRICTED,
                        batterieoptimierungIgnoriert = true,
                    ),
                letzterAusfallKlasse = null,
                letzterAusfallWaechterLief = null,
            )
        assertTrue(restrictedBefund.aktiv)
        assertTrue(HintergrundbeschraenkungGrund.STANDBY_BUCKET_EINGESCHRAENKT in restrictedBefund.gruende)
        assertEquals(45, restrictedBefund.standbyBucket)
    }

    @Test
    fun aktiveBatterieoptimierungWirdErkannt() {
        val befund =
            HintergrundbeschraenkungLogik.bewerten(
                signale =
                    HintergrundbeschraenkungSignale(
                        hintergrundEingeschraenkt = false,
                        standbyBucket = 10,
                        batterieoptimierungIgnoriert = false,
                    ),
                letzterAusfallKlasse = null,
                letzterAusfallWaechterLief = null,
            )
        assertTrue(befund.aktiv)
        assertTrue(HintergrundbeschraenkungGrund.BATTERIEOPTIMIERUNG_AKTIV in befund.gruende)
    }

    @Test
    fun indirekterNachweisUeberWaechterSpurBeiStillstandGeraetLiefZeigtHuaweiWortlaut() {
        val befund =
            HintergrundbeschraenkungLogik.bewertenAusEinstellungen(
                signale =
                    HintergrundbeschraenkungSignale(
                        hintergrundEingeschraenkt = false,
                        standbyBucket = 10,
                        batterieoptimierungIgnoriert = true,
                    ),
                ausfallKlassifikationName = AusfallKlasse.STILLSTAND_GERAET_LIEF.name,
                ausfallWaechterLiefName = "nein",
            )
        assertTrue(befund.aktiv)
        assertTrue(befund.hatWaechterSpurBeleg)
        assertTrue(befund.zeigtHuaweiAppStartAnleitung)
        assertTrue(
            HintergrundbeschraenkungGrund.WAECHTER_BLOCKIERT_BEI_LAUFENDEM_GERAET in befund.gruende,
        )

        // Gegenprobe: Wenn der Wächter lief (waechterLief = ja), wird der indirekte Hinweis nicht gezeigt.
        val befundMitLaufendemWaechter =
            HintergrundbeschraenkungLogik.bewertenAusEinstellungen(
                signale =
                    HintergrundbeschraenkungSignale(
                        hintergrundEingeschraenkt = false,
                        standbyBucket = 10,
                        batterieoptimierungIgnoriert = true,
                    ),
                ausfallKlassifikationName = AusfallKlasse.STILLSTAND_GERAET_LIEF.name,
                ausfallWaechterLiefName = "ja",
            )
        assertFalse(befundMitLaufendemWaechter.aktiv)
        assertFalse(befundMitLaufendemWaechter.hatWaechterSpurBeleg)
    }

    @Test
    fun appContainerVerwendetInjizierteFakeHintergrundbeschraenkungQuelle() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakeSignale =
            HintergrundbeschraenkungSignale(
                hintergrundEingeschraenkt = true,
                standbyBucket = 45,
                batterieoptimierungIgnoriert = false,
            )
        val container =
            AppContainer(
                context = context,
                hintergrundbeschraenkungQuelleOverride = HintergrundbeschraenkungQuelle { fakeSignale },
            )
        assertEquals(fakeSignale, container.hintergrundbeschraenkungQuelle.lesen())
    }

    @Test
    fun oeffneHuaweiAppStartFaelltBeiSecurityExceptionAufAppDetailsZurueck() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val versuchteIntents = mutableListOf<Intent>()

        HintergrundbeschraenkungLogik.oeffneHuaweiAppStartOderAppInfo(
            context = context,
            starter = { intent ->
                versuchteIntents.add(intent)
                if (intent.component?.packageName == "com.huawei.systemmanager") {
                    throw SecurityException("Permission Denial: Huawei StartupNormalAppListActivity")
                }
            },
        )

        assertEquals(2, versuchteIntents.size)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, versuchteIntents.last().action)
    }

    @Test
    fun oeffneHuaweiAppStartWirftKeineExceptionWennAlleKandidatenScheitern() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        HintergrundbeschraenkungLogik.oeffneHuaweiAppStartOderAppInfo(
            context = context,
            starter = { throw ActivityNotFoundException("Kein Einstellungsdialog vorhanden") },
        )
    }
}
