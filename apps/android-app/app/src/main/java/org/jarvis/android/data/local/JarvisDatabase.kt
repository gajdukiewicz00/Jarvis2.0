package org.jarvis.android.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [PendingItem::class], version = 1, exportSchema = false)
abstract class JarvisDatabase : RoomDatabase() {
    abstract fun pendingItems(): PendingItemDao

    companion object {
        @Volatile private var instance: JarvisDatabase? = null

        fun get(context: Context): JarvisDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                JarvisDatabase::class.java,
                "jarvis.db"
            ).build().also { instance = it }
        }
    }
}
