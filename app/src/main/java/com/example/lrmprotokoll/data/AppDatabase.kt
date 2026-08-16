package com.example.lrmprotokoll.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
