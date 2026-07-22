package com.gofilm.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.gofilm.app.GoFilmApp
import com.gofilm.app.data.dto.BannerItem
import com.gofilm.app.data.dto.FilmCard
import com.gofilm.app.data.dto.IndexData
import com.gofilm.app.data.dto.IndexSection
import com.gofilm.app.data.local.WatchHistoryEntity
import com.gofilm.app.ui.components.ErrorBox
import com.gofilm.app.ui.components.FilmRow
import com.gofilm.app.ui.components.HistoryRow
import com.gofilm.app.ui.components.LoadingBox
import com.gofilm.app.ui.components.SectionTitle
import com.gofilm.app.ui.theme.Accent
import com.gofilm.app.ui.theme.BgCard
import com.gofilm.app.ui.theme.Primary
import com.gofilm.app.ui.theme.TextMuted
import com.gofilm.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenDetail: (Long) -> Unit,
    onOpenSearch: () -> Unit,
    onContinuePlay: (WatchHistoryEntity) -> Unit = {},
    onOpenHistory: () -> Unit = {}
) {
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // 用 remember 保持首页数据，从详情返回时不丢列表
    var data by remember { mutableStateOf<IndexData?>(HomeCache.data) }
    var refreshKey by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val history by GoFilmApp.instance.localRepository.observeHistory()
        .collectAsState(initial = emptyList())

    fun reload(isPull: Boolean = false) {
        scope.launch {
            if (isPull) {
                refreshing = true
            } else if (data == null) {
                loading = true
            }
            // 有缓存时静默刷新，避免整页被 loading 清掉
            error = null
            GoFilmApp.instance.filmRepository.index()
                .onSuccess {
                    data = it
                    HomeCache.data = it
                    loading = false
                    refreshing = false
                }
                .onFailure {
                    // 失败时保留旧数据，避免「点一下只剩一部」的错觉
                    if (data == null) {
                        error = it.message ?: "加载失败"
                    } else {
                        error = "刷新失败：${it.message ?: "网络异常"}"
                    }
                    loading = false
                    refreshing = false
                }
        }
    }

    LaunchedEffect(refreshKey) { reload(false) }

    Column(Modifier.fillMaxSize()) {
        Text(
            text = "Alucard影视",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Accent,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
        Box(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .height(42.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(BgCard)
                .clickable(onClick = onOpenSearch)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text("搜索影片、演员、导演", color = TextMuted, fontSize = 14.sp)
        }

        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { reload(true) },
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            when {
                loading && data == null -> LoadingBox()
                error != null && data == null -> ErrorBox(
                    message = error!!,
                    onRetry = { refreshKey++ }
                )
                else -> {
                    val sections = data?.content.orEmpty().filter { section ->
                        section.nav?.show != false &&
                            (section.movies.orEmpty().isNotEmpty() || section.hot.orEmpty().isNotEmpty())
                    }
                    val banners = data?.banners.orEmpty()
                    // 继续观看只展示横滑条，不替代整页内容
                    val recentHistory = history.take(8)
                    val firstMovies = sections.firstOrNull()?.movies.orEmpty()
                    val firstHot = sections.firstOrNull()?.hot.orEmpty()

                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(top = 14.dp, bottom = 24.dp)
                    ) {
                        if (error != null) {
                            Text(
                                error!!,
                                color = Color(0xFFFF8A80),
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                        }

                        if (banners.isNotEmpty()) {
                            BannerCarousel(banners, onOpenDetail = onOpenDetail)
                            Spacer(Modifier.height(18.dp))
                        } else {
                            // 后台未配置横幅时：用有封面的热门/最新片顶上
                            val bannerFilm = (firstHot + firstMovies)
                                .firstOrNull { it.picture.isNotBlank() }
                                ?: firstMovies.firstOrNull()
                                ?: firstHot.firstOrNull()
                            if (bannerFilm != null) {
                                FilmBanner(bannerFilm) { onOpenDetail(bannerFilm.filmId) }
                                Spacer(Modifier.height(18.dp))
                            }
                        }

                        if (recentHistory.isNotEmpty()) {
                            SectionTitle("继续观看", action = "全部", onAction = onOpenHistory)
                            HistoryRow(recentHistory, onClick = onContinuePlay)
                            Spacer(Modifier.height(16.dp))
                        }

                        sections.forEach { section ->
                            SectionBlock(section, onOpenDetail)
                            Spacer(Modifier.height(12.dp))
                        }

                        if (sections.isEmpty() && banners.isEmpty() && recentHistory.isEmpty()) {
                            Text(
                                "暂无影片数据。\n请在管理后台完成采集后下拉刷新。",
                                color = TextSecondary,
                                modifier = Modifier.padding(24.dp),
                                fontSize = 14.sp
                            )
                        } else if (sections.isEmpty() && recentHistory.isNotEmpty()) {
                            Text(
                                "首页推荐暂不可用，可先从「继续观看」进入，或下拉刷新。",
                                color = TextSecondary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 进程内缓存，避免从详情返回时首页空白 */
object HomeCache {
    var data: IndexData? = null
}

@Composable
private fun SectionBlock(section: IndexSection, onOpenDetail: (Long) -> Unit) {
    val title = section.nav?.name?.ifBlank { "推荐" } ?: "推荐"
    val movies = section.movies.orEmpty().filter { it.filmId > 0L && it.name.isNotBlank() }
    val hot = section.hot.orEmpty().filter { it.filmId > 0L && it.name.isNotBlank() }
    // 热播 + 最新都展示，避免首页看起来「只有一半」
    if (hot.isNotEmpty()) {
        SectionTitle("$title · 热播")
        FilmRow(hot) { onOpenDetail(it.filmId) }
    }
    if (movies.isNotEmpty()) {
        SectionTitle(if (hot.isNotEmpty()) "$title · 最新" else title)
        FilmRow(movies) { onOpenDetail(it.filmId) }
    }
}

@Composable
private fun BannerCarousel(
    banners: List<BannerItem>,
    onOpenDetail: (Long) -> Unit = {}
) {
    val valid = banners.filter {
        it.name.isNotBlank() || it.poster.isNotBlank() || it.picture.isNotBlank()
    }
    if (valid.isEmpty()) return
    var page by remember { mutableIntStateOf(0) }
    LaunchedEffect(valid.size) {
        if (valid.size <= 1) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(4200)
            page = (page + 1) % valid.size
        }
    }
    val item = valid[page.coerceIn(0, valid.lastIndex)]
    val img = item.poster.ifBlank { item.picture }
    Box(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .height(168.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(
                    listOf(Primary.copy(alpha = 0.55f), Accent.copy(alpha = 0.25f), Color(0xFF1A1530))
                )
            )
            .clickable(enabled = item.mid > 0L) {
                if (item.mid > 0L) onOpenDetail(item.mid)
            }
    ) {
        if (img.isNotBlank()) {
            AsyncImage(
                model = img,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))
                    )
                )
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
        ) {
            Text(
                "今日推荐",
                color = Color(0xFF1A1000),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(Accent, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(item.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(
                listOf(item.cName, item.remark, item.year).filter { it.isNotBlank() }.joinToString(" · "),
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 12.sp
            )
        }
        if (valid.size > 1) {
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                valid.indices.forEach { i ->
                    Box(
                        Modifier
                            .height(5.dp)
                            .width(if (i == page) 14.dp else 5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                if (i == page) Accent else Color.White.copy(alpha = 0.45f)
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun FilmBanner(film: FilmCard, onClick: () -> Unit) {
    val pic = film.picture.trim()
    Box(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .height(168.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(
                    listOf(Primary.copy(alpha = 0.55f), Accent.copy(alpha = 0.25f), Color(0xFF1A1530))
                )
            )
            .clickable(onClick = onClick)
    ) {
        if (pic.isNotBlank()) {
            AsyncImage(
                model = pic,
                contentDescription = film.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))
                    )
                )
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
        ) {
            Text(
                "今日推荐",
                color = Color(0xFF1A1000),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(Accent, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(film.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(
                listOf(film.cName, film.remarks, film.year).filter { it.isNotBlank() }.joinToString(" · "),
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 12.sp
            )
        }
    }
}
