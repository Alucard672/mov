package com.gofilm.app.data.repo

import com.gofilm.app.data.local.AppDao
import com.gofilm.app.data.local.FavoriteEntity
import com.gofilm.app.data.local.WatchHistoryEntity
import kotlinx.coroutines.flow.Flow

class LocalRepository(private val dao: AppDao) {

    fun observeHistory(): Flow<List<WatchHistoryEntity>> = dao.observeHistory()
    fun observeFavorites(): Flow<List<FavoriteEntity>> = dao.observeFavorites()
    fun observeIsFavorite(filmId: Long): Flow<Boolean> = dao.observeIsFavorite(filmId)

    suspend fun getHistory(filmId: Long): WatchHistoryEntity? = dao.getHistory(filmId)

    suspend fun saveProgress(
        filmId: Long,
        name: String,
        picture: String,
        playFrom: String,
        episode: Int,
        episodeLabel: String,
        positionMs: Long,
        durationMs: Long
    ) {
        if (filmId <= 0L || name.isBlank()) return
        dao.upsertHistory(
            WatchHistoryEntity(
                filmId = filmId,
                name = name,
                picture = picture,
                playFrom = playFrom,
                episode = episode,
                episodeLabel = episodeLabel,
                positionMs = positionMs.coerceAtLeast(0L),
                durationMs = durationMs.coerceAtLeast(0L),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun deleteHistory(filmId: Long) = dao.deleteHistory(filmId)
    suspend fun clearHistory() = dao.clearHistory()

    suspend fun toggleFavorite(
        filmId: Long,
        name: String,
        picture: String,
        remarks: String
    ): Boolean {
        return if (dao.isFavorite(filmId)) {
            dao.deleteFavorite(filmId)
            false
        } else {
            dao.upsertFavorite(
                FavoriteEntity(
                    filmId = filmId,
                    name = name,
                    picture = picture,
                    remarks = remarks,
                    createdAt = System.currentTimeMillis()
                )
            )
            true
        }
    }

    suspend fun removeFavorite(filmId: Long) = dao.deleteFavorite(filmId)
}
