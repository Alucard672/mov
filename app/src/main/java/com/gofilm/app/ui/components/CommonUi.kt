package com.gofilm.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.gofilm.app.GoFilmApp
import com.gofilm.app.data.dto.FilmCard
import com.gofilm.app.data.local.FavoriteEntity
import com.gofilm.app.data.local.WatchHistoryEntity
import com.gofilm.app.ui.theme.Accent
import com.gofilm.app.ui.theme.BgCard
import com.gofilm.app.ui.theme.TextMuted
import com.gofilm.app.ui.theme.TextSecondary

/** 过滤本地已标记为播放失效的影片（默认隐藏） */
@Composable
fun rememberAliveFilms(films: List<FilmCard>, hideDead: Boolean = true): List<FilmCard> {
    val dead by GoFilmApp.instance.playabilityCache.deadMidsFlow.collectAsState(initial = emptySet())
    if (!hideDead || dead.isEmpty()) return films
    return films.filter { it.filmId !in dead }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Accent)
    }
}

@Composable
fun ErrorBox(message: String, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, color = TextSecondary, fontSize = 14.sp)
            if (onRetry != null) {
                Text(
                    "点击重试",
                    color = Accent,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .clickable(onClick = onRetry)
                        .padding(8.dp)
                )
            }
        }
    }
}

@Composable
fun SectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        if (action != null && onAction != null) {
            Text(
                action,
                color = TextMuted,
                fontSize = 12.sp,
                modifier = Modifier.clickable(onClick = onAction)
            )
        }
    }
}

@Composable
fun FilmPosterCard(
    film: FilmCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Int = 108,
    showDeadBadge: Boolean = false
) {
    Column(
        modifier = modifier
            .width(width.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(12.dp))
                .background(BgCard)
        ) {
            val ctx = LocalContext.current
            val pic = film.picture.trim()
            if (pic.isNotBlank()) {
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(ctx)
                        .data(pic)
                        .crossfade(true)
                        .build(),
                    contentDescription = film.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    loading = {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("…", color = TextMuted, fontSize = 12.sp)
                        }
                    },
                    error = {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(film.name.take(1).ifEmpty { "F" }, color = Accent, fontSize = 28.sp)
                        }
                    }
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(film.name.take(1).ifEmpty { "F" }, color = Accent, fontSize = 28.sp)
                }
            }
            if (showDeadBadge) {
                Text(
                    "失效",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(Color(0xCCE53935), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            if (film.remarks.isNotBlank()) {
                Text(
                    film.remarks,
                    color = Accent,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(BgCard.copy(alpha = 0.85f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        Text(
            film.name.ifBlank { "未命名" },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
        val sub = listOf(film.year, film.area, film.cName).filter { it.isNotBlank() }.joinToString(" · ")
        if (sub.isNotBlank()) {
            Text(sub, color = TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun FilmRow(
    films: List<FilmCard>,
    /** true=隐藏已确认失效的片；false=显示并打「失效」标签 */
    hideDead: Boolean = true,
    onClick: (FilmCard) -> Unit
) {
    val visible = rememberAliveFilms(films, hideDead = hideDead)
    val dead by GoFilmApp.instance.playabilityCache.deadMidsFlow.collectAsState(initial = emptySet())
    // 固定高度，避免嵌在 verticalScroll 里时 LazyRow 只剩 1 个 item 的布局异常
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(
            items = visible,
            key = { film -> "f_${film.filmId}" }
        ) { film ->
            FilmPosterCard(
                film = film,
                onClick = { onClick(film) },
                showDeadBadge = !hideDead && film.filmId in dead
            )
        }
    }
}

@Composable
fun FilmGrid(
    films: List<FilmCard>,
    modifier: Modifier = Modifier,
    hideDead: Boolean = true,
    onClick: (FilmCard) -> Unit
) {
    val visible = rememberAliveFilms(films, hideDead = hideDead)
    val dead by GoFilmApp.instance.playabilityCache.deadMidsFlow.collectAsState(initial = emptySet())
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        items(visible, key = { "${it.filmId}_${it.name}" }) { film ->
            FilmPosterCard(
                film = film,
                onClick = { onClick(film) },
                modifier = Modifier.fillMaxWidth(),
                width = 120,
                showDeadBadge = !hideDead && film.filmId in dead
            )
        }
    }
}

@Composable
fun HistoryRow(items: List<WatchHistoryEntity>, onClick: (WatchHistoryEntity) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(items, key = { it.filmId }) { item ->
            Column(
                Modifier
                    .width(108.dp)
                    .clickable { onClick(item) }
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.72f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(BgCard)
                ) {
                    if (item.picture.isNotBlank()) {
                        AsyncImage(
                            model = item.picture,
                            contentDescription = item.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                Text(
                    item.episodeLabel.ifBlank { "第${item.episode + 1}集" },
                    color = TextMuted,
                    fontSize = 11.sp
                )
                val progress = if (item.durationMs > 0) {
                    (item.positionMs.toFloat() / item.durationMs).coerceIn(0f, 1f)
                } else 0f
                if (progress > 0f) {
                    LinearProgressIndicator(
                        progress = { progress },
                        color = Accent,
                        trackColor = BgCard,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .height(3.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun HistoryListItem(
    item: WatchHistoryEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(64.dp)
                .height(90.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(BgCard)
        ) {
            if (item.picture.isNotBlank()) {
                AsyncImage(
                    model = item.picture,
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${item.episodeLabel.ifBlank { "第${item.episode + 1}集" }} · 继续观看",
                color = TextMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
            val progress = if (item.durationMs > 0) {
                (item.positionMs.toFloat() / item.durationMs).coerceIn(0f, 1f)
            } else 0f
            if (progress > 0f) {
                LinearProgressIndicator(
                    progress = { progress },
                    color = Accent,
                    trackColor = BgCard,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .height(3.dp)
                )
            }
        }
        Text(
            "删除",
            color = TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier
                .clickable(onClick = onDelete)
                .padding(8.dp)
        )
    }
}

@Composable
fun FavoriteListItem(
    item: FavoriteEntity,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(64.dp)
                .height(90.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(BgCard)
        ) {
            if (item.picture.isNotBlank()) {
                AsyncImage(
                    model = item.picture,
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.remarks.isNotBlank()) {
                Text(item.remarks, color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Text(
            "取消",
            color = TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier
                .clickable(onClick = onRemove)
                .padding(8.dp)
        )
    }
}
