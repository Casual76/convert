package com.p2r3.convert.data.history

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [HistoryEntryEntity::class],
    version = 2,
    exportSchema = false
)
abstract class ConvertDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
}
