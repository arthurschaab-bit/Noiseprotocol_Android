package com.example.lrmprotokoll.wetter

/**
 * Hand-geschriebener Ersatz fuer [WetterProvider] (kein Mockito/MockK in diesem Repo) - fuer
 * Tests von [com.example.lrmprotokoll.ui.GesamtberichtStammdatenSheet], ohne echtes Netzwerk.
 * Genau der in [WetterProvider]s KDoc angekuendigte Fake. Gleiche Konvention wie
 * [com.example.lrmprotokoll.meter.FakeMeterTransport]: ueber ein `var`-Feld von aussen auf einen
 * bestimmten Erfolgs- oder Fehlschlagfall einstellbar.
 */
class FakeWetterProvider(
    var ergebnis: Result<Wetterlage> = Result.success(
        Wetterlage(temperaturCelsius = 12.0, windgeschwindigkeitKmh = 8.0, beschreibung = "bedeckt"),
    ),
) : WetterProvider {

    override suspend fun aktuelleWetterlage(breitengrad: Double, laengengrad: Double): Result<Wetterlage> = ergebnis
}
