package com.example.speedup.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [FieldMappingEntity::class], version = 1, exportSchema = false)
abstract class SpeedUpDatabase : RoomDatabase() {
    abstract fun fieldMappingDao(): FieldMappingDao

    companion object {
        @Volatile
        private var instance: SpeedUpDatabase? = null

        fun get(context: Context): SpeedUpDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SpeedUpDatabase::class.java,
                    "speedup.db"
                ).build().also { instance = it }
            }
    }
}
