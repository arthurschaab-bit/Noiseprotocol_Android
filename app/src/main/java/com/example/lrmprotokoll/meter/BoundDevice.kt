package com.example.lrmprotokoll.meter

/**
 * Nach der Erstkopplung persistiertes Geraet (Plan Abschnitt 6, Geraete-Pinning). Es wird
 * ausschliesslich zu dieser Adresse verbunden; ein Advertiser mit gleichem Namen, aber
 * anderer Adresse, wird ignoriert.
 */
data class BoundDevice(
    val address: String,
    val name: String,
)
