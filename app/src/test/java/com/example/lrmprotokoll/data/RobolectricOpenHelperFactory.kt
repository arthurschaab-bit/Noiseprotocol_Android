package com.example.lrmprotokoll.data

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory

/**
 * OpenHelper-Factory fuer MigrationTestHelper unter Robolectric auf Windows und Linux.
 *
 * Hintergrund: Room 2.8 prueft in SupportSQLiteDriver.open(fileName), ob der uebergebene Pfad mit
 * dem konfigurierten databaseName uebereinstimmt. MigrationTestHelper uebergibt den absoluten
 * Dateipfad (context.getDatabasePath(name).absolutePath). Durch Uebernahme des absoluten Pfads
 * in die Configuration stimmen beide ueberein.
 */
class RobolectricOpenHelperFactory : SupportSQLiteOpenHelper.Factory {
    private val delegate = FrameworkSQLiteOpenHelperFactory()

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val fullPath = configuration.name?.let { configuration.context.getDatabasePath(it).absolutePath }
        val newConfig = SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(fullPath)
            .callback(configuration.callback)
            .build()
        return delegate.create(newConfig)
    }
}
