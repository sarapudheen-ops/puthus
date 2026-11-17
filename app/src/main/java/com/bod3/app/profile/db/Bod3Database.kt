package com.bod3.app.profile.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [VehicleProfileEntity::class, ModuleEntity::class, RawLogEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class Bod3Database : RoomDatabase() {
    abstract fun profileDao(): ProfileDao

    companion object {
        @Volatile
        private var instance: Bod3Database? = null

        fun get(context: Context): Bod3Database = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, Bod3Database::class.java, "bod3.db")
                .build()
                .also { instance = it }
        }
    }
}
