package com.example.lrmprotokoll.ui

/**
 * Zusätzliche, herstellereigene Autostart-/"Geschützte Apps"-Einstellung außerhalb des
 * Standard-Android-Akkuoptimierungssystems (PROMPT_M8.md Aufgabe 2). Ohne diese Freischaltung
 * killt das ROM den Foreground Service trotz gewährter Akkuoptimierungs-Ausnahme (Plan
 * Abschnitt 13, "Hersteller-ROM killt den Foreground Service").
 *
 * [intentPackage]/[intentActivity] sind aus öffentlich dokumentierten Referenzimplementierungen
 * des Android-Fragmentierungsproblems übernommen (u.a. bekannte Open-Source-"Autostart-
 * Permission"-Bibliotheken) - sie sind NICHT auf jeder ROM-Version garantiert vorhanden, daher
 * beim Aufruf immer mit ActivityNotFoundException-Fallback verwenden (siehe
 * [OemDeviceHelperCard]).
 */
data class OemAutostartHinweis(
    val hinweistext: String,
    val intentPackage: String,
    val intentActivity: String,
)

private const val XIAOMI_PACKAGE = "com.miui.securitycenter"
private const val XIAOMI_ACTIVITY = "com.miui.permcenter.autostart.AutoStartManagementActivity"

private const val HUAWEI_PACKAGE = "com.huawei.systemmanager"
private const val HUAWEI_ACTIVITY = "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"

private const val OPPO_PACKAGE = "com.coloros.safecenter"
private const val OPPO_ACTIVITY = "com.coloros.safecenter.permission.startup.StartupAppListActivity"

private const val VIVO_PACKAGE = "com.vivo.permissionmanager"
private const val VIVO_ACTIVITY = "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"

private const val ONEPLUS_PACKAGE = "com.oneplus.security"
private const val ONEPLUS_ACTIVITY = "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"

// Samsung hat kein einzelnes globales "Autostart"-Menü wie die anderen Hersteller - "Geräte-
// Wartung" existiert nicht auf jeder One-UI-Version/Region gleich, daher laut Auftrag nur
// "teils" abgedeckt: Hinweistext macht das Unsichere explizit statt Zuverlässigkeit vorzutaeuschen.
private const val SAMSUNG_PACKAGE = "com.samsung.android.lool"
private const val SAMSUNG_ACTIVITY = "com.samsung.android.sm.ui.battery.BatteryActivity"

/**
 * Reine Ableitungsfunktion (analog zum Muster in messreihe/DashboardStatus.kt): [hersteller]
 * kommt als Parameter rein statt Build.MANUFACTURER direkt in der Funktion zu lesen - macht die
 * Zuordnung ohne Robolectric/Gerät per JVM-Unit-Test prüfbar. Liefert null, wenn der Hersteller
 * keine bekannte zusätzliche Autostart-Sperre hat (z.B. reines AOSP/Pixel/unbekannt).
 */
fun leiteOemAutostartHinweisAb(hersteller: String): OemAutostartHinweis? {
    val h = hersteller.trim()
    return when {
        h.contains("Xiaomi", ignoreCase = true) ||
            h.contains("Redmi", ignoreCase = true) ||
            h.contains("POCO", ignoreCase = true) ->
            OemAutostartHinweis("Xiaomi / HyperOS Autostart prüfen", XIAOMI_PACKAGE, XIAOMI_ACTIVITY)

        h.contains("Huawei", ignoreCase = true) ->
            OemAutostartHinweis("Huawei / EMUI Geschützte Apps prüfen", HUAWEI_PACKAGE, HUAWEI_ACTIVITY)

        h.contains("Oppo", ignoreCase = true) ->
            OemAutostartHinweis("Oppo / ColorOS Autostart prüfen", OPPO_PACKAGE, OPPO_ACTIVITY)

        h.contains("Vivo", ignoreCase = true) ->
            OemAutostartHinweis("Vivo Autostart prüfen", VIVO_PACKAGE, VIVO_ACTIVITY)

        h.contains("OnePlus", ignoreCase = true) ->
            OemAutostartHinweis("OnePlus / OxygenOS Autostart prüfen", ONEPLUS_PACKAGE, ONEPLUS_ACTIVITY)

        h.contains("Samsung", ignoreCase = true) ->
            OemAutostartHinweis(
                "Samsung Geräte-Wartung: „Nicht überwachte Apps“ prüfen (nicht auf jeder One-UI-Version vorhanden)",
                SAMSUNG_PACKAGE,
                SAMSUNG_ACTIVITY,
            )

        else -> null
    }
}
