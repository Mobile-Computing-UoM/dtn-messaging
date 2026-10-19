package com.oppmesh.dtn.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [PacketEntity::class, ContactLogEntity::class, ContactEntity::class],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class OppMeshDatabase : RoomDatabase() {
    abstract fun packetDao(): PacketDao
    abstract fun contactLogDao(): ContactLogDao
    abstract fun contactDao(): ContactDao

    companion object {
        @Volatile
        private var INSTANCE: OppMeshDatabase? = null

        fun getInstance(context: Context): OppMeshDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    OppMeshDatabase::class.java,
                    "oppmesh_dtn.db"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
