package com.gofilm.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "watch_history")
data class WatchHistoryEntity(
    @PrimaryKey val filmId: Long,
    val name: String,
    val picture: String,
    val playFrom: String,
    val episode: Int,
    val episodeLabel: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val filmId: Long,
    val name: String,
    val picture: String,
    val remarks: String,
    val createdAt: Long
)
