package com.p2r3.convert.data.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Query("SELECT * FROM conversion_history ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<HistoryEntryEntity>>

    @Query("SELECT * FROM conversion_history WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): HistoryEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: HistoryEntryEntity): Long

    @Update
    suspend fun update(entity: HistoryEntryEntity)

    @Query("DELETE FROM conversion_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM conversion_history")
    suspend fun clearAll()
}
