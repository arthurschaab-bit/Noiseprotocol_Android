package com.example.lrmprotokoll.diagnose.export

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.FileProvider
import com.example.lrmprotokoll.BuildConfig
import com.example.lrmprotokoll.data.AppDatabase
import com.example.lrmprotokoll.data.DiagnosticLogDao
import com.example.lrmprotokoll.data.SettingsManager
import com.example.lrmprotokoll.diagnose.ANR_TRACE_DATEINAME
import com.example.lrmprotokoll.diagnose.BreadcrumbRingFile
import com.example.lrmprotokoll.diagnose.DiagnosticRedactor
import com.example.lrmprotokoll.diagnose.DiagnosticsReporter
import com.example.lrmprotokoll.diagnose.NATIVE_TOMBSTONE_DATEINAME
import com.example.lrmprotokoll.diagnose.ProcessExitInfo
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/** Bundle-Typen (Konzept 4.4/4.7) - stehen im Dateinamen und in `manifest.json`. */
enum class BundleTyp(val bezeichnung: String) {
    ABSTURZ("absturz"),
    ANR("anr"),
    PERIODISCH("periodisch"),
    MANUELL("manuell"),
}

/**
 * Bundle-spezifischer Kontext, den nur der jeweilige Ausloeser kennt (Absturz-Sender in
 * Schritt 5, periodischer Worker in Schritt 6). Fuer einen manuellen Export aus dem
 * DiagnoseScreen bleiben die Crash-Felder `null`.
 */
data class BundleKontext(
    val typ: BundleTyp,
    val ausloeser: String,
    val acraReportJson: String? = null,
    val threadDetails: String? = null,
    /** `null` liest Logcat live (fuer manuelle/periodische Bundles); bei einem Absturz-Bundle
     * uebergibt der ACRA-Sender den bereits von ACRA gesammelten Text (Schritt 5). */
    val logcatText: String? = null,
    val exitInfos: List<ProcessExitInfo> = emptyList(),
    /** M12 Schritt 6 (Konzept Aufgabe 3): fertiges JSON der [HealthMetrics] des periodischen
     * Gesundheits-Bundles. `null` fuer alle anderen Bundle-Typen. */
    val healthMetricsJson: String? = null,
)

private const val SEITENGROESSE = 500
private const val MAX_KUERZUNGSSTUFE = 2

// Einzelobergrenzen aus Konzept 4.5 - unkomprimiert, beim Schreiben geprueft (nicht erst am
// fertigen ZIP, sonst ist der Speicher schon verbraucht, den das schuetzen soll).
private const val LOGCAT_MAX_ABSTURZ = 8L * 1024 * 1024
private const val LOGCAT_MAX_PERIODISCH = 512L * 1024
private const val EVENTS_MAX_ABSTURZ = 16L * 1024 * 1024
private const val EVENTS_MAX_PERIODISCH = 1L * 1024 * 1024
private const val BREADCRUMBS_MAX = 512L * 1024

// Fertiges-ZIP-Budget (Konzept 4.5, Owner-Entscheidung O-6).
private const val ZIP_BUDGET_ABSTURZ = 10L * 1024 * 1024
private const val ZIP_BUDGET_PERIODISCH = 2L * 1024 * 1024

/**
 * Erzeugt Support-Bundles (ZIP-Archive) fuer Diagnose und Support-Faelle (M12 Schritt 4, Konzept
 * Abschnitt 4.4 & 4.5 - behebt Luecke L7).
 *
 * Struktur: `manifest.json`, `crash/`, `log/`, `state/`, `checksums.sha256`.
 *
 * Selbstschutz gegen OOM (Konzept 4.5, weil Speichermangel der Hauptverdaechtige fuer den
 * akuten Absturz ist): jeder Eintrag wird direkt in den [ZipOutputStream] geschrieben
 * (Pruefsummen ueber einen [DigestOutputStream] im Vorbeigehen), `events.jsonl` liest
 * [DiagnosticLogDao] seitenweise statt die ganze Tabelle auf einmal in den Heap zu ziehen. Jeder
 * Sammelschritt ist einzeln abgesichert - scheitert einer, entsteht das Bundle trotzdem, mit
 * Fehlervermerk in `manifest.json`.
 */
class SupportBundleExporter(
    private val context: Context,
    private val reporter: DiagnosticsReporter,
    private val diagnosticLogDao: DiagnosticLogDao,
    private val breadcrumbRingFile: BreadcrumbRingFile,
    private val settingsManager: SettingsManager,
    private val database: AppDatabase,
    private val traceVerzeichnis: File,
    private val bleVerbindungszustandProvider: () -> String = { "UNBEKANNT" },
    private val aufnahmeAktivProvider: () -> Boolean = { false },
) {

    suspend fun createBundle(kontext: BundleKontext): File {
        reporter.breadcrumb("SupportBundle", "Erstelle Bundle (${kontext.typ.bezeichnung}, ${kontext.ausloeser})")
        return baueBundle(kontext, kuerzungsstufe = 0)
    }

    private suspend fun baueBundle(kontext: BundleKontext, kuerzungsstufe: Int): File {
        val baseDir = context.getExternalFilesDir(null) ?: context.cacheDir
        val bundleDir = File(baseDir, "support_bundles").apply { mkdirs() }
        if (kuerzungsstufe == 0) {
            bundleDir.listFiles()?.forEach { if (it.isFile && it.name.endsWith(".zip")) it.delete() }
        }

        // Dateinamensschema aus Konzept 4.6 (Schritt 5, Ablage auf Drive):
        // JJJJ-MM-TT_HHMMSS_<typ>.zip - derselbe Name wird spaeter unveraendert nach Drive
        // hochgeladen (SupportBundleUploadWorker), deshalb hier bereits in diesem Format statt
        // einer spaeteren Umbenennung.
        val zeitstempel = System.currentTimeMillis()
        val dateiStempel = java.text.SimpleDateFormat("yyyy-MM-dd_HHmmss", java.util.Locale.US)
            .format(java.util.Date(zeitstempel))
        val zipFile = File(bundleDir, "${dateiStempel}_${kontext.typ.bezeichnung}.zip")

        val istPeriodisch = kontext.typ == BundleTyp.PERIODISCH
        val zipBudget = if (istPeriodisch) ZIP_BUDGET_PERIODISCH else ZIP_BUDGET_ABSTURZ
        // Kuerzungsreihenfolge (Konzept 4.5): zuerst events.jsonl, dann zusaetzlich logcat.txt,
        // crash/ wird nie gekuerzt - dafuer existiert das Bundle.
        val eventsMax = if (kuerzungsstufe >= 1) 0L else if (istPeriodisch) EVENTS_MAX_PERIODISCH else EVENTS_MAX_ABSTURZ
        val logcatMax = if (kuerzungsstufe >= 2) 0L else if (istPeriodisch) LOGCAT_MAX_PERIODISCH else LOGCAT_MAX_ABSTURZ

        val fehler = mutableListOf<String>()
        if (kuerzungsstufe > 0) {
            fehler.add("Budget ueberschritten - gekuerzt auf Stufe $kuerzungsstufe (0=alles, 1=ohne events.jsonl, 2=zusaetzlich ohne logcat.txt)")
        }

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            val checksums = LinkedHashMap<String, String>()

            suspend fun schreibeEintrag(name: String, block: suspend (OutputStream) -> Unit) {
                runCatching {
                    val digest = MessageDigest.getInstance("SHA-256")
                    zos.putNextEntry(ZipEntry(name))
                    val digestOut = DigestOutputStream(zos, digest)
                    block(digestOut)
                    digestOut.flush()
                    zos.closeEntry()
                    checksums[name] = digest.digest().joinToString("") { "%02x".format(it) }
                }.onFailure { fehler.add("$name: ${it.javaClass.simpleName} ${it.message}") }
            }

            // crash/
            kontext.acraReportJson?.let { inhalt ->
                schreibeEintrag("crash/acra_report.json") { it.write(inhalt.toByteArray(StandardCharsets.UTF_8)) }
            }
            kontext.threadDetails?.let { inhalt ->
                schreibeEintrag("crash/threads.txt") { it.write(DiagnosticRedactor.redactString(inhalt).orEmpty().toByteArray(StandardCharsets.UTF_8)) }
            }
            if (kontext.exitInfos.isNotEmpty()) {
                schreibeEintrag("crash/exit_info.json") { it.write(buildExitInfoJson(kontext.exitInfos).toByteArray(StandardCharsets.UTF_8)) }
            }
            File(traceVerzeichnis, ANR_TRACE_DATEINAME).takeIf { it.exists() }?.let { quelle ->
                schreibeEintrag("crash/anr_trace.txt") { out -> quelle.inputStream().use { it.copyTo(out) } }
            }
            File(traceVerzeichnis, NATIVE_TOMBSTONE_DATEINAME).takeIf { it.exists() }?.let { quelle ->
                schreibeEintrag("crash/native_tombstone.pb") { out -> quelle.inputStream().use { it.copyTo(out) } }
            }

            // log/
            schreibeEintrag("log/logcat.txt") { out -> schreibeLogcat(out, kontext, logcatMax) }
            schreibeEintrag("log/breadcrumbs.jsonl") { out -> schreibeBreadcrumbs(out) }
            schreibeEintrag("log/events.jsonl") { out -> schreibeEventsSeitenweise(out, eventsMax) }

            // state/
            schreibeEintrag("state/runtime.json") { it.write(buildRuntimeJson().toByteArray(StandardCharsets.UTF_8)) }
            schreibeEintrag("state/settings.json") { it.write(buildSettingsJson().toByteArray(StandardCharsets.UTF_8)) }
            schreibeEintrag("state/db_stats.json") { it.write(buildDbStatsJson().toByteArray(StandardCharsets.UTF_8)) }
            kontext.healthMetricsJson?.let { inhalt ->
                schreibeEintrag("state/health_metrics.json") { it.write(inhalt.toByteArray(StandardCharsets.UTF_8)) }
            }

            // manifest.json zuletzt - sammelt die bis hierhin aufgelaufenen Fehler ein.
            schreibeEintrag("manifest.json") { it.write(buildManifestJson(kontext, kuerzungsstufe, fehler).toByteArray(StandardCharsets.UTF_8)) }

            runCatching {
                zos.putNextEntry(ZipEntry("checksums.sha256"))
                zos.write(checksums.entries.joinToString("") { (name, hex) -> "$hex  $name\n" }.toByteArray(StandardCharsets.UTF_8))
                zos.closeEntry()
            }
        }

        if (zipFile.length() > zipBudget && kuerzungsstufe < MAX_KUERZUNGSSTUFE) {
            zipFile.delete()
            return baueBundle(kontext, kuerzungsstufe + 1)
        }
        return zipFile
    }

    fun createShareIntent(zipFile: File): Intent {
        val authority = "${context.packageName}.fileprovider"
        val contentUri = FileProvider.getUriForFile(context, authority, zipFile)

        return Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            // Bugfix (Owner-Feedback 12.09.2026): kein Dateiname mehr in Klammern im Betreff -
            // manche Ziel-Apps (Speichern/Teilen-Ziele) leiten daraus einen eigenen, dann doppelt
            // verketteten und viel zu langen Dateinamen ab (siehe zipFile-Benennung oben).
            putExtra(Intent.EXTRA_SUBJECT, "Lärmprotokoll Support-Bundle")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun buildManifestJson(kontext: BundleKontext, kuerzungsstufe: Int, fehler: List<String>): String {
        val json = JSONObject()
        json.put("typ", kontext.typ.bezeichnung)
        json.put("ausloeser", kontext.ausloeser)
        json.put("erstelltAm", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
        json.put("appVersion", BuildConfig.VERSION_NAME)
        json.put("versionCode", BuildConfig.VERSION_CODE)
        json.put("buildType", BuildConfig.BUILD_TYPE)
        json.put("kuerzungsstufe", kuerzungsstufe)
        val fehlerArray = JSONArray()
        fehler.forEach { fehlerArray.put(it) }
        json.put("fehler", fehlerArray)
        return json.toString(2)
    }

    private fun buildExitInfoJson(exits: List<ProcessExitInfo>): String {
        val array = JSONArray()
        for (exit in exits) {
            val json = JSONObject()
            json.put("reason", exit.reason)
            json.put("status", exit.status)
            json.put("timestamp", exit.timestamp)
            json.put("importance", exit.importance)
            json.put("pss", exit.pss)
            json.put("rss", exit.rss)
            json.put("description", exit.description?.let { DiagnosticRedactor.redactString(it) } ?: JSONObject.NULL)
            json.put("processName", DiagnosticRedactor.redactString(exit.processName))
            json.put("definingUid", exit.definingUid)
            array.put(json)
        }
        return array.toString(2)
    }

    private fun schreibeLogcat(out: OutputStream, kontext: BundleKontext, maxBytes: Long) {
        if (maxBytes <= 0) return
        val rohtext = kontext.logcatText ?: leseAktuellesLogcatLive(maxBytes)
        val bereinigt = DiagnosticRedactor.redactString(rohtext).orEmpty()
        val bytes = bereinigt.toByteArray(StandardCharsets.UTF_8)
        out.write(bytes, 0, minOf(bytes.size.toLong(), maxBytes).toInt())
    }

    private fun leseAktuellesLogcatLive(maxBytes: Long): String = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "threadtime"))
        process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
            val sb = StringBuilder()
            var groesse = 0L
            var zeile = reader.readLine()
            while (zeile != null && groesse < maxBytes) {
                sb.append(zeile).append('\n')
                groesse += zeile.toByteArray(StandardCharsets.UTF_8).size + 1
                zeile = reader.readLine()
            }
            sb.toString()
        }
    }.getOrElse { "" }

    private fun schreibeBreadcrumbs(out: OutputStream) {
        var geschrieben = 0L
        for (bc in breadcrumbRingFile.lesen()) {
            val json = JSONObject()
            json.put("timestamp", bc.timestampMillis)
            json.put("isoTime", DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(bc.timestampMillis)))
            json.put("category", bc.category)
            json.put("message", DiagnosticRedactor.redactString(bc.message))
            json.put("level", bc.level)
            val dataObj = JSONObject()
            DiagnosticRedactor.redactMap(bc.data).forEach { (k, v) -> dataObj.put(k, v ?: JSONObject.NULL) }
            json.put("data", dataObj)
            val zeile = (json.toString() + "\n").toByteArray(StandardCharsets.UTF_8)
            if (geschrieben + zeile.size > BREADCRUMBS_MAX) break
            out.write(zeile)
            geschrieben += zeile.size
        }
    }

    /**
     * Liest [DiagnosticLogDao] seitenweise (Aufgabe 2) - nie mehr als [SEITENGROESSE] Zeilen
     * gleichzeitig im Heap, egal wie gross die Tabelle ist. Genau das ersetzt den alten Weg
     * (`createBundle(diagnosticLogs: List<DiagnosticLogEntity>)`), der die ganze Tabelle auf
     * einmal in den Heap zog.
     */
    private suspend fun schreibeEventsSeitenweise(out: OutputStream, maxBytes: Long) {
        if (maxBytes <= 0) return
        var nachId = 0L
        var geschrieben = 0L
        while (true) {
            val seite = diagnosticLogDao.seite(nachId, SEITENGROESSE)
            if (seite.isEmpty()) break
            for (log in seite) {
                val json = JSONObject()
                json.put("id", log.id)
                json.put("timestamp", log.timestamp)
                json.put("isoTime", DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(log.timestamp)))
                json.put("message", DiagnosticRedactor.redactString(log.message))
                val zeile = (json.toString() + "\n").toByteArray(StandardCharsets.UTF_8)
                if (geschrieben + zeile.size > maxBytes) return
                out.write(zeile)
                geschrieben += zeile.size
            }
            nachId = seite.last().id
            if (seite.size < SEITENGROESSE) break
        }
    }

    private fun buildRuntimeJson(): String {
        val json = JSONObject()
        val runtime = Runtime.getRuntime()
        json.put("heapUsedBytes", runtime.totalMemory() - runtime.freeMemory())
        json.put("heapFreeBytes", runtime.freeMemory())
        json.put("heapMaxBytes", runtime.maxMemory())

        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am != null) {
                val memInfo = ActivityManager.MemoryInfo()
                am.getMemoryInfo(memInfo)
                json.put("systemAvailMemBytes", memInfo.availMem)
                json.put("systemTotalMemBytes", memInfo.totalMem)
                json.put("systemLowMemory", memInfo.lowMemory)
                json.put("systemMemoryThresholdBytes", memInfo.threshold)
            }
        }

        runCatching {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            if (batteryManager != null) {
                json.put("batteryPercent", batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            }
        }

        runCatching {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager != null) {
                json.put("batterieoptimierungIgnoriert", powerManager.isIgnoringBatteryOptimizations(context.packageName))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    json.put("geraetImDozeModus", powerManager.isDeviceIdleMode)
                }
            }
        }

        runCatching {
            val berechtigungen = JSONObject()
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            val namen = packageInfo.requestedPermissions
            val flags = packageInfo.requestedPermissionsFlags
            namen?.forEachIndexed { i, name ->
                val gewaehrt = ((flags?.getOrNull(i) ?: 0) and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                berechtigungen.put(name, gewaehrt)
            }
            json.put("berechtigungen", berechtigungen)
        }

        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val dienste = JSONArray()
            @Suppress("DEPRECATION")
            am?.getRunningServices(Integer.MAX_VALUE)?.forEach { dienste.put(it.service.className) }
            json.put("laufendeDienste", dienste)
        }

        json.put("bleVerbindungszustand", runCatching { bleVerbindungszustandProvider() }.getOrDefault("UNBEKANNT"))
        json.put("aufnahmeAktiv", runCatching { aufnahmeAktivProvider() }.getOrDefault(false))

        return json.toString(2)
    }

    private fun buildSettingsJson(): String {
        val json = JSONObject()
        val redacted = DiagnosticRedactor.redactMap(settingsManager.unverschluesselteEinstellungenSnapshot())
        redacted.forEach { (k, v) -> json.put(k, v ?: JSONObject.NULL) }
        return json.toString(2)
    }

    private fun buildDbStatsJson(): String {
        val json = JSONObject()
        return runCatching {
            val db = database.openHelper.readableDatabase
            val tabellen = JSONArray()
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%'").use { cursor ->
                while (cursor.moveToNext()) {
                    val tabellenname = cursor.getString(0)
                    val anzahl = db.query("SELECT COUNT(*) FROM `$tabellenname`").use { c2 -> if (c2.moveToFirst()) c2.getLong(0) else 0L }
                    tabellen.put(JSONObject().put("tabelle", tabellenname).put("zeilen", anzahl))
                }
            }
            json.put("tabellen", tabellen)
            // Der Room-DB-Dateiname ("noise_database") ist AppDatabase.getDatabase()s
            // Room.databaseBuilder()-Argument (AppDatabase.kt) - hier bewusst dupliziert statt
            // einer neuen Konstante, um Schritt 4 nicht ueber die Diagnose-Dateien hinaus
            // anzufassen.
            val dbDatei = context.getDatabasePath("noise_database")
            json.put("dateiGroesseBytes", if (dbDatei.exists()) dbDatei.length() else 0L)
            json.toString(2)
        }.getOrElse {
            json.put("fehler", it.message ?: it.javaClass.simpleName)
            json.toString(2)
        }
    }
}
