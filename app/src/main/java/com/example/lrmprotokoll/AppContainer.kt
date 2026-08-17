package com.example.lrmprotokoll

import android.content.Context
import com.example.lrmprotokoll.data.AppDatabase
import com.example.lrmprotokoll.data.SettingsManager
import com.example.lrmprotokoll.meter.MeterTransport
import com.example.lrmprotokoll.meter.ble.BleMeterTransport

/**
 * Schlanker manueller Ersatz fuer ein DI-Framework (Plan Abschnitt 4.2): haelt die
 * Anwendung geteilten Abhaengigkeiten an einer Stelle, damit sie in Tests austauschbar sind,
 * ohne alle bestehenden Klassen mit Annotationen zu versehen.
 */
class AppContainer(context: Context) {
    val database: AppDatabase by lazy { AppDatabase.getDatabase(context) }
    val settingsManager: SettingsManager by lazy { SettingsManager(context) }
    val meterTransport: MeterTransport by lazy { BleMeterTransport(context.applicationContext) }
}
