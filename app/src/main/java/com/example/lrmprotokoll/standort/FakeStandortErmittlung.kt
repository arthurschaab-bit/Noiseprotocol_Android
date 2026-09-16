package com.example.lrmprotokoll.standort

/**
 * Hand-geschriebener Ersatz fuer [StandortErmittlung] (kein Mockito/MockK in diesem Repo) - fuer
 * Tests von [com.example.lrmprotokoll.ui.GesamtberichtStammdatenSheet], das echte GPS-/Geocoder-
 * Hardware braeuchte. Gleiche Konvention wie [com.example.lrmprotokoll.meter.FakeMeterTransport]:
 * ueber `var`-Felder von aussen auf einen bestimmten Erfolgs- oder Fehlschlagfall einstellbar.
 */
class FakeStandortErmittlung(
    var standort: Standort? = Standort(breitengrad = 52.5, laengengrad = 13.4),
    var adresse: String? = "Musterstraße 1, 12345 Berlin",
) : StandortErmittlung {

    override fun aktuellerStandort(): Standort? = standort

    override suspend fun adresseFuer(standort: Standort): String? = adresse
}
