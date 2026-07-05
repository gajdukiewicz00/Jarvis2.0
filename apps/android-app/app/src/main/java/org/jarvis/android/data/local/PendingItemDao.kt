package org.jarvis.android.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingItemDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: PendingItem)

    @Query("SELECT * FROM pending_items WHERE syncedAtEpochMs IS NULL ORDER BY createdAtEpochMs ASC")
    suspend fun pending(): List<PendingItem>

    @Query("SELECT * FROM pending_items ORDER BY createdAtEpochMs DESC LIMIT :limit")
    fun recent(limit: Int = 50): Flow<List<PendingItem>>

    @Query("UPDATE pending_items SET syncedAtEpochMs = :ts WHERE id = :id")
    suspend fun markSynced(id: String, ts: Long)

    @Query("UPDATE pending_items SET attemptCount = attemptCount + 1, lastAttemptEpochMs = :ts, lastError = :err WHERE id = :id")
    suspend fun markFailed(id: String, ts: Long, err: String)

    @Query("DELETE FROM pending_items WHERE syncedAtEpochMs IS NOT NULL AND syncedAtEpochMs < :ts")
    suspend fun pruneSyncedBefore(ts: Long)
}
