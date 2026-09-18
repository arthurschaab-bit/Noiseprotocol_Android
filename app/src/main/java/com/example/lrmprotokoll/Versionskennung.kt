package com.example.lrmprotokoll

/**
 * Saubere Versionskennung fuer CI-/Debug-Builds (docs/PROMPT_VERSIONSKENNUNG.md).
 *
 * [formatiere] und [istReleaseBuild] nehmen versionName/versionCode als Parameter entgegen statt
 * sie aus [BuildConfig] zu lesen - so lassen sie sich ohne Robolectric als reine JVM-Unit-Tests
 * gegen alle Faelle aus Abschnitt 3.1 pruefen. [aktuelleKennung]/[istAktuellerBuildRelease] sind
 * der schlanke Convenience-Zugriff darueber, der tatsaechlich [BuildConfig] liest.
 */
object Versionskennung {
    private val releaseMuster = Regex("""^\d+\.\d+\.\d+$""")

    /** Die Zeichenkette fuer Zwischenablage/Exporte, z.B. "1.0.0-pr181.ci342+a1b2c3d (342)". */
    fun formatiere(
        versionName: String,
        versionCode: Int,
    ): String = "$versionName ($versionCode)"

    /** true nur beim reinen X.Y.Z ohne Suffix - der Release-Fall aus Abschnitt 3.1. */
    fun istReleaseBuild(versionName: String): Boolean = releaseMuster.matches(versionName)

    fun aktuelleKennung(): String = formatiere(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)

    fun istAktuellerBuildRelease(): Boolean = istReleaseBuild(BuildConfig.VERSION_NAME)
}
