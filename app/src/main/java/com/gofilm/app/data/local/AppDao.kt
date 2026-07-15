package com.gofilm.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {

    // ---- history ----
    @Query("SELECT * FROM watch_history ORDER BY updatedAt DESC")
    fun observeHistory(): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history WHERE filmId = :filmId LIMIT 1")
    suspend fun getHistory(filmId: Long): WatchHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHistory(item: WatchHistoryEntity)

    @Query("DELETE FROM watch_history WHERE filmId = :filmId")
    suspend fun deleteHistory(filmId: Long)

    @Query("DELETE FROM watch_history")
    suspend fun clearHistory()

    // ---- favorites ----
    @Query("SELECT * FROM favorites ORDER BY createdAt DESC")
    fun observeFavorites(): Flow<List<FavoriteEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE filmId = :filmId)")
    fun observeIsFavorite(filmId: Long): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE filmId = :filmId)")
    suspend fun isFavorite(filmId: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFavorite(item: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE filmId = :filmId")
    suspend fun deleteFavorite(filmId: Long)
}
