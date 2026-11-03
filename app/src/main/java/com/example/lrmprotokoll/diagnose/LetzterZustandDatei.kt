package com.example.lrmprotokoll.diagnose

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import com.example.lrmprotokoll.data.SettingsManager
import org.json.JSONObject
import java.io.File

/** Ausschliesslich technische Zahlen und Flags aus der Zeit vor einem moeglichen Ausfall. */
data class LetzterZustand(
    val zeitpunkt: Long,
    val akkuProzent: Int?,
    val stromquelle: String?,
    val akkuStatus: String?,
    val uptimeMs: Long,
    val bootCount: Int?,
    val letzteDatenAt: Long?,
    val bleZustand: String,
    val mikrofonAktiv: Boolean,
    val dienstLaeuft: Boolean,
    val sauberBeendet: Boolean,
    val dozeAktiv: Boolean?,
    val energiesparModus: Boolean?,
    val hintergrundEingeschraenkt: Boolean?,
    val standbyBucket: Int?,
    val waechterLetzterLaufAt: Long?,
    val heartbeatLetzterErfolgAt: Long?,
) {
    fun alsJson(): JSONObject =
        JSONObject().apply {
            put("zeitpunkt", zeitpunkt)
            put("akkuProzent", akkuProzent ?: JSONObject.NULL)
            put("stromquelle", stromquelle ?: JSONObject.NULL)
            put("akkuStatus", akkuStatus ?: JSONObject.NULL)
            put("uptimeMs", uptimeMs)
            put("bootCount", bootCount ?: JSONObject.NULL)
            put("letzteDatenAt", letzteDatenAt ?: JSONObject.NULL)
            put("bleZustand", bleZustand)
            put("mikrofonAktiv", mikrofonAktiv)
            put("dienstLaeuft", dienstLaeuft)
            put("sauberBeendet", sauberBeendet)
            put("dozeAktiv", dozeAktiv ?: JSONObject.NULL)
            put("energiesparModus", energiesparModus ?: JSONObject.NULL)
            put("hintergrundEingeschraenkt", hintergrundEingeschraenkt ?: JSONObject.NULL)
            put("standbyBucket", standbyBucket ?: JSONObject.NULL)
            put("waechterLetzterLaufAt", waechterLetzterLaufAt ?: JSONObject.NULL)
            put("heartbeatLetzterErfolgAt", heartbeatLetzterErfolgAt ?: JSONObject.NULL)
        }

    companion object {
        fun ausJson(json: JSONObject): LetzterZustand =
            LetzterZustand(
                zeitpunkt = json.getLong("zeitpunkt"),
                akkuProzent = json.nullableInt("akkuProzent"),
                stromquelle = json.nullableString("stromquelle"),
                akkuStatus = json.nullableString("akkuStatus"),
                uptimeMs = json.getLong("uptimeMs"),
                bootCount = json.nullableInt("bootCount"),
                letzteDatenAt = json.nullableLong("letzteDatenAt"),
                bleZustand = json.getString("bleZustand"),
                mikrofonAktiv = json.getBoolean("mikrofonAktiv"),
                dienstLaeuft = json.getBoolean("dienstLaeuft"),
                sauberBeendet = json.getBoolean("sauberBeendet"),
                dozeAktiv = json.nullableBoolean("dozeAktiv"),
                energiesparModus = json.nullableBoolean("energiesparModus"),
                hintergrundEingeschraenkt = json.nullableBoolean("hintergrundEingeschraenkt"),
                standbyBucket = json.nullableInt("standbyBucket"),
                waechterLetzterLaufAt = json.nullableLong("waechterLetzterLaufAt"),
                heartbeatLetzterErfolgAt = json.nullableLong("heartbeatLetzterErfolgAt"),
            )
    }
}

private fun JSONObject.nullableLong(key: String): Long? = if (isNull(key)) null else getLong(key)

private fun JSONObject.nullableInt(key: String): Int? = if (isNull(key)) null else getInt(key)

private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else getString(key)

private fun JSONObject.nullableBoolean(key: String): Boolean? = if (isNull(key)) null else getBoolean(key)

/** AtomicFile schreibt zuerst eine .new-Datei und ersetzt danach die bisherige JSON-Datei. */
class LetzterZustandDatei(
    datei: File,
) {
    private val atomicFile = AtomicFile(datei)

    @Synchronized
    fun lesen(): LetzterZustand? =
        runCatching {
            atomicFile.openRead().bufferedReader().use { LetzterZustand.ausJson(JSONObject(it.readText())) }
        }.getOrNull()

    @Synchronized
    fun schreiben(zustand: LetzterZustand) {
        val stream = atomicFile.startWrite()
        try {
            stream.write(zustand.alsJson().toString().toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(stream)
        } catch (fehler: Throwable) {
            atomicFile.failWrite(stream)
            throw fehler
        }
    }
}

data class SystemZustand(
    val zeitpunkt: Long,
    val akkuProzent: Int?,
    val stromquelle: String?,
    val akkuStatus: String?,
    val uptimeMs: Long,
    val bootCount: Int?,
    val dozeAktiv: Boolean?,
    val energiesparModus: Boolean?,
    val hintergrundEingeschraenkt: Boolean?,
    val standbyBucket: Int?,
)

/** Android-Zugriffe sind austauschbar; fehlende Signale bleiben null statt erfunden zu werden. */
fun interface SystemZustandQuelle {
    fun erfassen(): SystemZustand
}

class AndroidSystemZustandQuelle(
    private val context: Context,
) : SystemZustandQuelle {
    override fun erfassen(): SystemZustand {
        val strom =
            runCatching {
                context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let(::parseStromzustand)
            }.getOrNull()
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        return SystemZustand(
            zeitpunkt = System.currentTimeMillis(),
            akkuProzent = strom?.prozent,
            stromquelle = strom?.quelle?.name,
            akkuStatus = strom?.status?.name,
            uptimeMs = SystemClock.elapsedRealtime(),
            bootCount = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrNull(),
            dozeAktiv = runCatching { power?.isDeviceIdleMode }.getOrNull(),
            energiesparModus = runCatching { power?.isPowerSaveMode }.getOrNull(),
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
        )
    }
}

/** Haelt das letzte Daten-Zeitfeld zwischen Strom-, BLE- und Lebenszeichen-Updates fest. */
class LetzterZustandErfasser(
    private val datei: LetzterZustandDatei,
    private val system: SystemZustandQuelle,
    private val settings: SettingsManager,
    private val bleZustand: () -> String,
    private val mikrofonAktiv: () -> Boolean,
    private val dienstLaeuft: () -> Boolean,
) {
    @Synchronized
    fun aktualisieren(
        letzteDatenAt: Long? = null,
        sauberBeendet: Boolean? = null,
    ) {
        val vorher = datei.lesen()
        val signal = system.erfassen()
        datei.schreiben(
            LetzterZustand(
                zeitpunkt = signal.zeitpunkt,
                akkuProzent = signal.akkuProzent,
                stromquelle = signal.stromquelle,
                akkuStatus = signal.akkuStatus,
                uptimeMs = signal.uptimeMs,
                bootCount = signal.bootCount,
                letzteDatenAt = letzteDatenAt ?: vorher?.letzteDatenAt,
                bleZustand = bleZustand(),
                mikrofonAktiv = mikrofonAktiv(),
                dienstLaeuft = dienstLaeuft(),
                sauberBeendet = sauberBeendet ?: vorher?.sauberBeendet ?: false,
                dozeAktiv = signal.dozeAktiv,
                energiesparModus = signal.energiesparModus,
                hintergrundEingeschraenkt = signal.hintergrundEingeschraenkt,
                standbyBucket = signal.standbyBucket,
                waechterLetzterLaufAt = settings.waechterLetzterLaufAt.takeIf { it > 0L },
                heartbeatLetzterErfolgAt = settings.heartbeatLetzterErfolgAt.takeIf { it > 0L },
            ),
        )
    }
}
