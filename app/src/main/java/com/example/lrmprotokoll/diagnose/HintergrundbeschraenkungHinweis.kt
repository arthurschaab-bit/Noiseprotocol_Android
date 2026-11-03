package com.example.lrmprotokoll.diagnose

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import java.util.Locale

private const val HUAWEI_SYSTEMMANAGER_PACKAGE = "com.huawei.systemmanager"
private const val HUAWEI_STARTUP_ACTIVITY = "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"

/**
 * Kapselt die Systemabfragen zur Hintergrundbeschränkung hinter einer kleinen Schnittstelle
 * (Schritt 7 aus docs/PROMPT_DIAGNOSE_STILLSTAND.md), damit Tests ohne echtes Gerät oder
 * Hersteller-ROM alle Zweige über ein handgeschriebenes Fake prüfen können.
 */
data class HintergrundbeschraenkungSignale(
    val hintergrundEingeschraenkt: Boolean?,
    val standbyBucket: Int?,
    val batterieoptimierungIgnoriert: Boolean?,
)

fun interface HintergrundbeschraenkungQuelle {
    fun lesen(): HintergrundbeschraenkungSignale
}

class AndroidHintergrundbeschraenkungQuelle(
    private val context: Context,
) : HintergrundbeschraenkungQuelle {
    override fun lesen(): HintergrundbeschraenkungSignale {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        return HintergrundbeschraenkungSignale(
            hintergrundEingeschraenkt =
                if (Build.VERSION.SDK_INT >= 28) {
                    runCatching { activity?.isBackgroundRestricted }.getOrNull()
                } else {
                    null
                },
            standbyBucket =
                if (Build.VERSION.SDK_INT >= 28) {
                    runCatching { usage?.appStandbyBucket }.getOrNull()
                } else {
                    null
                },
            batterieoptimierungIgnoriert =
                runCatching { power?.isIgnoringBatteryOptimizations(context.packageName) }.getOrNull(),
        )
    }
}

enum class HintergrundbeschraenkungGrund {
    /** Indirekter Beleg nach Ausfall: Gerät lief (`STILLSTAND_GERAET_LIEF`/`FRAMEWORK_NEUSTART`), aber Wächter lief nicht. */
    WAECHTER_BLOCKIERT_BEI_LAUFENDEM_GERAET,

    /** `ActivityManager.isBackgroundRestricted() == true`. */
    HINTERGRUND_EINGESCHRAENKT,

    /** `UsageStatsManager.getAppStandbyBucket()` ist `STANDBY_BUCKET_RARE` (40) oder `RESTRICTED` (45). */
    STANDBY_BUCKET_EINGESCHRAENKT,

    /** `PowerManager.isIgnoringBatteryOptimizations() == false`. */
    BATTERIEOPTIMIERUNG_AKTIV,
}

data class HintergrundbeschraenkungBefund(
    val aktiv: Boolean,
    val gruende: List<HintergrundbeschraenkungGrund>,
    val standbyBucket: Int? = null,
) {
    val hatWaechterSpurBeleg: Boolean
        get() = HintergrundbeschraenkungGrund.WAECHTER_BLOCKIERT_BEI_LAUFENDEM_GERAET in gruende

    val zeigtHuaweiAppStartAnleitung: Boolean
        get() =
            hatWaechterSpurBeleg ||
                HintergrundbeschraenkungGrund.HINTERGRUND_EINGESCHRAENKT in gruende ||
                HintergrundbeschraenkungGrund.STANDBY_BUCKET_EINGESCHRAENKT in gruende
}

/**
 * Reine Bewertungslogik für Schritt 7 ("Hinweis auf Hintergrundbeschränkung"):
 * - Direkt: `isBackgroundRestricted()` (API 28), Standby-Bucket (`RARE` = 40, `RESTRICTED` = 45),
 *   Batterieoptimierung (`batterieoptimierungIgnoriert == false`).
 * - Indirekt: Nach einem Ausfall mit Klasse `STILLSTAND_GERAET_LIEF` oder `FRAMEWORK_NEUSTART`
 *   zeigt die Wächter-Spur, dass keine Jobs liefen (`waechterLief == NEIN`).
 */
object HintergrundbeschraenkungLogik {
    const val STANDBY_BUCKET_RARE = 40
    const val STANDBY_BUCKET_RESTRICTED = 45
    const val MAX_AUSFALL_ALTER_TAGE = 7L
    const val MAX_AUSFALL_ALTER_MS = MAX_AUSFALL_ALTER_TAGE * 24L * 60L * 60_000L

    private val KLASSEN_MIT_LAUFENDEM_GERAET =
        setOf(
            AusfallKlasse.STILLSTAND_GERAET_LIEF,
            AusfallKlasse.FRAMEWORK_NEUSTART,
        )

    fun bewerten(
        signale: HintergrundbeschraenkungSignale,
        letzterAusfallKlasse: AusfallKlasse?,
        letzterAusfallWaechterLief: WaechterLief?,
        letzterAusfallLetzteDatenAt: Long = 0L,
        jetztMs: Long = 0L,
        weggewischteAusfaelle: Set<Long> = emptySet(),
    ): HintergrundbeschraenkungBefund {
        val gruende = mutableListOf<HintergrundbeschraenkungGrund>()

        val nichtWeggewischt = letzterAusfallLetzteDatenAt !in weggewischteAusfaelle
        val nichtVeraltet =
            letzterAusfallLetzteDatenAt <= 0L ||
                jetztMs <= 0L ||
                (jetztMs - letzterAusfallLetzteDatenAt) <= MAX_AUSFALL_ALTER_MS

        if (letzterAusfallKlasse in KLASSEN_MIT_LAUFENDEM_GERAET &&
            letzterAusfallWaechterLief == WaechterLief.NEIN &&
            nichtWeggewischt &&
            nichtVeraltet
        ) {
            gruende += HintergrundbeschraenkungGrund.WAECHTER_BLOCKIERT_BEI_LAUFENDEM_GERAET
        }
        if (signale.hintergrundEingeschraenkt == true) {
            gruende += HintergrundbeschraenkungGrund.HINTERGRUND_EINGESCHRAENKT
        }
        val bucket = signale.standbyBucket
        if (bucket != null && bucket >= STANDBY_BUCKET_RARE) {
            gruende += HintergrundbeschraenkungGrund.STANDBY_BUCKET_EINGESCHRAENKT
        }
        if (signale.batterieoptimierungIgnoriert == false) {
            gruende += HintergrundbeschraenkungGrund.BATTERIEOPTIMIERUNG_AKTIV
        }

        return HintergrundbeschraenkungBefund(
            aktiv = gruende.isNotEmpty(),
            gruende = gruende,
            standbyBucket = bucket,
        )
    }

    fun bewertenAusEinstellungen(
        signale: HintergrundbeschraenkungSignale,
        ausfallKlassifikationName: String?,
        ausfallWaechterLiefName: String?,
        ausfallLetzteDatenAt: Long = 0L,
        jetztMs: Long = 0L,
        weggewischteAusfaelle: Set<Long> = emptySet(),
    ): HintergrundbeschraenkungBefund {
        val klasse =
            ausfallKlassifikationName?.let {
                runCatching { AusfallKlasse.valueOf(it) }.getOrNull()
            }
        val waechter =
            ausfallWaechterLiefName?.uppercase(Locale.ROOT)?.let {
                runCatching { WaechterLief.valueOf(it) }.getOrNull()
            }
        return bewerten(
            signale = signale,
            letzterAusfallKlasse = klasse,
            letzterAusfallWaechterLief = waechter,
            letzterAusfallLetzteDatenAt = ausfallLetzteDatenAt,
            jetztMs = jetztMs,
            weggewischteAusfaelle = weggewischteAusfaelle,
        )
    }

    /**
     * Öffnet die Huawei-App-Start-Verwaltung mit `try/catch` und fällt bei jedem Fehler
     * (`ActivityNotFoundException`, `SecurityException` bei nicht exportierter Activity auf EMUI)
     * auf die allgemeinen App-Infos (`ACTION_APPLICATION_DETAILS_SETTINGS`) zurück.
     */
    fun oeffneHuaweiAppStartOderAppInfo(
        context: Context,
        starter: (Intent) -> Unit = { context.startActivity(it) },
    ) {
        val huaweiIntent =
            Intent().apply {
                setClassName(HUAWEI_SYSTEMMANAGER_PACKAGE, HUAWEI_STARTUP_ACTIVITY)
            }
        try {
            starter(huaweiIntent)
            return
        } catch (_: Exception) {
            // EMUI/HarmonyOS benennt oder sperrt die Activity je nach Version anders -> Rückfall auf App-Infos.
        }
        val fallbackIntent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
        runCatching { starter(fallbackIntent) }
    }
}
