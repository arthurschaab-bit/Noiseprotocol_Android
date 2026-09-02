package com.example.lrmprotokoll.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
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


/**
 * Migration von Schema-Version 6 auf 7: die Tabelle `alerts` fuer M5 (Alarmierung).
 *
 * Rein additiv - es wird keine bestehende Tabelle angefasst, keine Spalte umbenannt, kein
 * Datensatz beruehrt. Aufgezeichnete Laermereignisse und gelernte Referenzgeraeusche bleiben
 * dadurch nachweislich unveraendert; der Migrationstest prueft genau das.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Muss dem exportierten Schema 7.json entsprechen, sonst schlaegt Rooms Validierung
        // beim Oeffnen fehl - inklusive der NOT-NULL-Angaben und der Default-Werte.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `alerts` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`sessionId` INTEGER, " +
                "`outageSince` INTEGER NOT NULL, " +
                "`raisedAt` INTEGER, " +
                "`resolvedAt` INTEGER, " +
                "`reason` TEXT NOT NULL, " +
                "`recipients` TEXT NOT NULL, " +
                "`deliveryState` TEXT NOT NULL, " +
                "`attempts` INTEGER NOT NULL, " +
                "`escalations` INTEGER NOT NULL)"
        )
    }
}

/**
 * Alle Migrationen an einer Stelle.
 *
 * Der Umweg ueber eine Konstante ist kein Selbstzweck: Die Migrationstests registrieren ihre
 * Migrationen selbst, weil sie die Datenbank ueber einen eigenen Builder oeffnen. Stuenden die
 * Migrationen nur im Builder unten, wuerde jede neue Migration die Tests stillschweigend an der
 * Produktionsliste vorbeilaufen lassen - genau das ist beim Sprung auf Version 7 passiert und
 * haette einen gruenen Test trotz kaputtem Upgrade-Pfad ergeben koennen.
 */

/**
 * Migration von Schema-Version 7 auf 8: die Tabellen `level_samples` und `drive_daily_files`
 * fuer M7b (Google-Drive-Sync).
 *
 * Rein additiv wie die Migration zuvor - keine bestehende Tabelle wird angefasst.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `level_samples` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`at` INTEGER NOT NULL, " +
                "`levelDb` REAL NOT NULL, " +
                "`source` TEXT NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `drive_daily_files` (" +
                "`date` TEXT NOT NULL, " +
                "`fileId` TEXT, " +
                "`lastSyncedAt` INTEGER NOT NULL, " +
                "`lastRowCount` INTEGER NOT NULL, " +
                "`state` TEXT NOT NULL, " +
                "PRIMARY KEY(`date`))"
        )
    }
}
/**
 * Migration von Schema-Version 8 auf 9: die Messreihe fuer M4 (`sessions`, `measurements`,
 * `connection_events`, `minute_aggregates`) und drei neue Spalten auf `noise_records`
 * (`calibratedDbA`, `meterWeighting`, `meterConnected` - Plan 4.5, Trigger-Umstellung).
 *
 * Rein additiv wie die Migrationen zuvor.
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `noise_records` ADD COLUMN `calibratedDbA` REAL"
        )
        db.execSQL(
            "ALTER TABLE `noise_records` ADD COLUMN `meterWeighting` TEXT"
        )
        db.execSQL(
            "ALTER TABLE `noise_records` ADD COLUMN `meterConnected` INTEGER NOT NULL DEFAULT 0"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sessions` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`startedAt` INTEGER NOT NULL, " +
                "`endedAt` INTEGER, " +
                "`deviceAddress` TEXT NOT NULL, " +
                "`deviceName` TEXT NOT NULL, " +
                "`weighting` TEXT, " +
                "`timeWeighting` TEXT)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `measurements` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`sessionId` INTEGER NOT NULL, " +
                "`timestamp` INTEGER NOT NULL, " +
                "`levelDb` REAL NOT NULL, " +
                "`weighting` TEXT, " +
                "`flags` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `connection_events` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`sessionId` INTEGER NOT NULL, " +
                "`at` INTEGER NOT NULL, " +
                "`type` TEXT NOT NULL, " +
                "`reason` TEXT)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `minute_aggregates` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`sessionId` INTEGER NOT NULL, " +
                "`minuteStart` INTEGER NOT NULL, " +
                "`leqDb` REAL NOT NULL, " +
                "`maxDb` REAL NOT NULL, " +
                "`minDb` REAL NOT NULL, " +
                "`sampleCount` INTEGER NOT NULL, " +
                "`weighting` TEXT)"
        )
    }
}
/**
 * Migration von Schema-Version 9 auf 10: die Tabelle `diagnostic_log_entries` fuer M6
 * (standardmaessig abgeschaltetes Diagnose-Log, Plan Abschnitt 6). Rein additiv wie die
 * Migrationen zuvor.
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `diagnostic_log_entries` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`timestamp` INTEGER NOT NULL, " +
                "`message` TEXT NOT NULL)"
        )
    }
}
/**
 * Migration von Schema-Version 10 auf 11: `timeWeighting` und `range` auf `measurements` und
 * `minute_aggregates`, `range` auf `sessions` - vervollstaendigt die bereits vorhandenen
 * `weighting`-Spalten um die beiden anderen Geraeteeinstellungen (Owner-Wunsch: vollstaendige
 * Einstellungen im CSV-Export). Rein additiv wie die Migrationen zuvor.
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `sessions` ADD COLUMN `range` TEXT")
        db.execSQL("ALTER TABLE `measurements` ADD COLUMN `timeWeighting` TEXT")
        db.execSQL("ALTER TABLE `measurements` ADD COLUMN `range` TEXT")
        db.execSQL("ALTER TABLE `minute_aggregates` ADD COLUMN `timeWeighting` TEXT")
        db.execSQL("ALTER TABLE `minute_aggregates` ADD COLUMN `range` TEXT")
    }
}

/**
 * Migration von Schema-Version 11 auf 12: `isQuietHour`, `deletedAt`, `favorite`, `notes` auf `noise_records`
 * fuer M10 (F8 Ruhezeiten, F9 Papierkorb / Soft-Delete, F2 Filter & Favoriten).
 * Rein additiv wie die Migrationen zuvor.
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `noise_records` ADD COLUMN `isQuietHour` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `noise_records` ADD COLUMN `deletedAt` INTEGER")
        db.execSQL("ALTER TABLE `noise_records` ADD COLUMN `favorite` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `noise_records` ADD COLUMN `notes` TEXT")
    }
}

/**
 * Migration von Schema-Version 12 auf 13: KI-Umbau Etappe 1 (Beweisdokumentation Baulärm).
 *
 * Vier neue, nullable Spalten auf `noise_records` (Aufnahmebedingungen, Etappe 1.3) und die neue
 * Tabelle `klassifikations_rohdaten` (rohe YAMNet-Frame-Scores, Etappe 1.4). Rein additiv wie
 * die Migrationen zuvor - keine bestehende Spalte oder Tabelle wird angefasst, kein Datensatz
 * verändert. Altaufnahmen bleiben ohne Rohdaten (kein `klassifikations_rohdaten`-Eintrag) -
 * "Neu bewerten" überspringt sie dann, statt abzustürzen (Akzeptanzkriterium Etappe 1).
 */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `noise_records` ADD COLUMN `aufnahmeQuelle` INTEGER")
        db.execSQL("ALTER TABLE `noise_records` ADD COLUMN `abtastrate` INTEGER")
        db.execSQL("ALTER TABLE `noise_records` ADD COLUMN `kanalzahl` INTEGER")
        db.execSQL("ALTER TABLE `noise_records` ADD COLUMN `agcAktiv` INTEGER")

        // Identisch zum exportierten Schema 13.json, damit Rooms Validierung im Anschluss traegt.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `klassifikations_rohdaten` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`recordId` INTEGER NOT NULL, " +
                "`modellVersion` TEXT NOT NULL, " +
                "`klassifiziertAm` INTEGER NOT NULL, " +
                "`frameAnzahl` INTEGER NOT NULL, " +
                "`frameDauerMs` INTEGER NOT NULL, " +
                "`frameHopMs` INTEGER NOT NULL, " +
                "`klassenIndizes` TEXT NOT NULL, " +
                "`frameScores` BLOB NOT NULL, " +
                "`topKlassen` TEXT NOT NULL, " +
                "FOREIGN KEY(`recordId`) REFERENCES `noise_records`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_klassifikations_rohdaten_recordId` " +
                "ON `klassifikations_rohdaten` (`recordId`)"
        )
    }
}

/**
 * Migration von Schema-Version 13 auf 14: KI-Umbau Etappe 3.4 (Impulsanalyse).
 *
 * Fünf neue, nullable Spalten auf `klassifikations_rohdaten` (physikalische, YAMNet-unabhängige
 * Hüllkurven-Merkmale - siehe Entity-KDoc). Rein additiv wie die Migrationen zuvor - Altaufnahmen
 * (auch solche mit bereits vorhandenen Rohdaten aus Etappe 1/2) bleiben unverändert, die neuen
 * Spalten sind für sie schlicht `NULL`.
 */
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `klassifikations_rohdaten` ADD COLUMN `impulsCrest` REAL")
        db.execSQL("ALTER TABLE `klassifikations_rohdaten` ADD COLUMN `impulsKurtosis` REAL")
        db.execSQL("ALTER TABLE `klassifikations_rohdaten` ADD COLUMN `impulsWiederholrateHz` REAL")
        db.execSQL("ALTER TABLE `klassifikations_rohdaten` ADD COLUMN `impulsPeakSchaerfe` REAL")
        db.execSQL("ALTER TABLE `klassifikations_rohdaten` ADD COLUMN `impulsMittlererPegel` REAL")
    }
}

val ALLE_MIGRATIONEN = arrayOf(
    MIGRATION_4_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12,
    MIGRATION_12_13, MIGRATION_13_14,
)

@Database(
    entities = [
        NoiseRecord::class, ReferenceSound::class, AlertEntity::class,
        LevelSampleEntity::class, DriveDailyFileEntity::class,
        SessionEntity::class, MeasurementEntity::class, ConnectionEventEntity::class,
        MinuteAggregateEntity::class, DiagnosticLogEntity::class, KlassifikationsRohdaten::class,
    ],
    version = 14,
    exportSchema = true,
)
@TypeConverters(RohdatenConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun noiseDao(): NoiseDao

    abstract fun alertDao(): AlertDao

    abstract fun levelSampleDao(): LevelSampleDao

    abstract fun driveDailyFileDao(): DriveDailyFileDao

    abstract fun sessionDao(): SessionDao

    abstract fun measurementDao(): MeasurementDao

    abstract fun connectionEventDao(): ConnectionEventDao

    abstract fun minuteAggregateDao(): MinuteAggregateDao

    abstract fun diagnosticLogDao(): DiagnosticLogDao

    abstract fun klassifikationsRohdatenDao(): KlassifikationsRohdatenDao

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
                .addMigrations(*ALLE_MIGRATIONEN)
                .build()
                INSTANCE = instance
                instance
            }
        }

        /**
         * F13 (Sicherung/Wiederherstellung): nach dem Überschreiben der Datenbankdatei mit einer
         * Sicherung muss die nächste [getDatabase]-Anfrage eine frische Room-Instanz gegen die
         * neue Datei öffnen, statt die bereits geschlossene alte [INSTANCE] weiterzureichen.
         * Ersetzt NICHT die zwingende App-Neustart-Empfehlung nach einer Wiederherstellung - alle
         * Stellen, die vor dem Reset schon eine [AppDatabase]-Referenz (z.B. über
         * [com.example.lrmprotokoll.AppContainer]) gehalten haben, zeigen weiterhin auf die
         * geschlossene alte Instanz, bis der Prozess neu startet.
         */
        internal fun resetInstance() {
            synchronized(this) {
                INSTANCE = null
            }
        }
    }
}
