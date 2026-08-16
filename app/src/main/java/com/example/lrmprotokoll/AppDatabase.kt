package com.example.lrmprotokoll

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration von Schema-Version 4 auf 6.
 *
 * Hintergrund: Bis einschliesslich M-1 lief die Datenbank mit fallbackToDestructiveMigration().
 * Dadurch wurde eine aeltere Datenbank bei jedem Versionssprung stillschweigend geloescht und neu
 * angelegt, statt migriert zu werden. Auf Geraeten, die seither nicht neu installiert wurden, liegt
 * die Datenbank deshalb weiterhin auf Version 4 - mit allen aufgezeichneten Messwerten darin.
 *
 * Der auf einem realen Geraet ausgelesene v4-Stand lautet:
 *
 *   CREATE TABLE `noise_records` (
 *       `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
 *       `timestamp` INTEGER NOT NULL,
 *       `amplitude` REAL NOT NULL,
 *       `filePath` TEXT NOT NULL,
 *       `label` TEXT,
 *       `detectedLabel` TEXT)
 *
 * Gegenueber Version 6 fehlen also die Spalte `dbValue` und die Tabelle `reference_sounds`.
 * Beides laesst sich rein additiv ergaenzen, es geht kein einziger Datensatz verloren.
 *
 * Die Zwischenversion 5 wird uebersprungen: Wegen des destruktiven Fallbacks hat nie ein Geraet
 * einen migrierten v5-Stand gesehen, ein separater Schritt 4->5 haette also keinen Bestand, den er
 * migrieren koennte.
 */
val MIGRATION_4_6 = object : Migration(4, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Defensiv gegen Geraete, deren Datenbank zwar user_version 4 meldet, aber bereits eine
        // der Spalten traegt - etwa weil zwischenzeitlich eine Debug-Version installiert war.
        if (!hatSpalte(db, "noise_records", "dbValue")) {
            // DEFAULT ist beim Hinzufuegen einer NOT-NULL-Spalte in SQLite Pflicht. Room
            // vergleicht Default-Werte nur, wenn die Entity selbst einen deklariert
            // (@ColumnInfo(defaultValue = ...)) - das ist hier nicht der Fall, der Wert stoert
            // die Schemavalidierung nach der Migration also nicht.
            db.execSQL("ALTER TABLE `noise_records` ADD COLUMN `dbValue` REAL NOT NULL DEFAULT 0.0")
        }

        // Identisch zum exportierten Schema 6.json, damit Rooms Validierung im Anschluss traegt.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `reference_sounds` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`pattern` TEXT NOT NULL)"
        )
    }

    private fun hatSpalte(db: SupportSQLiteDatabase, tabelle: String, spalte: String): Boolean {
        db.query("PRAGMA table_info(`$tabelle`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            if (nameIndex < 0) return false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == spalte) return true
            }
        }
        return false
    }
}

@Database(entities = [NoiseRecord::class, ReferenceSound::class], version = 6, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun noiseDao(): NoiseDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "noise_database"
                )
                .addMigrations(MIGRATION_4_6)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
