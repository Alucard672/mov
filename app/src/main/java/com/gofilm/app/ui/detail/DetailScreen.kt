package com.gofilm.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.gofilm.app.GoFilmApp
import com.gofilm.app.data.dto.FilmDetailData
import com.gofilm.app.data.dto.PlaySource
import com.gofilm.app.ui.components.ErrorBox
import com.gofilm.app.ui.components.FilmRow
import com.gofilm.app.ui.components.LoadingBox
import com.gofilm.app.ui.components.SectionTitle
import com.gofilm.app.ui.theme.Accent
import com.gofilm.app.ui.theme.AccentSoft
import com.gofilm.app.ui.theme.Bg
import com.gofilm.app.ui.theme.BgCard
import com.gofilm.app.ui.theme.TextMuted
import com.gofilm.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    filmId: Long,
    onBack: () -> Unit,
    onPlay: (filmId: Long, playFrom: String, episode: Int) -> Unit,
    onOpenDetail: (Long) -> Unit
) {
    var loading by remember { mutableStateOf(true) }
    var probing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var data by remember { mutableStateOf<FilmDetailData?>(null) }
    var sourceIndex by remember { mutableIntStateOf(0) }
    var episodeIndex by remember { mutableIntStateOf(0) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var deadSourceIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var deadLinks by remember { mutableStateOf<Set<String>>(emptySet()) }
    var allDead by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val isFavorite by GoFilmApp.instance.localRepository.observeIsFavorite(filmId)
        .collectAsState(initial = false)

    LaunchedEffect(filmId, refreshKey) {
        loading = true
        probing = false
        error = null
        deadSourceIds = emptySet()
        deadLinks = emptySet()
        allDead = false
        GoFilmApp.instance.filmRepository.filmDetail(filmId)
            .onSuccess {
                data = it
                sourceIndex = 0
                episodeIndex = 0
                loading = false
                // 后台探测播放链接，过滤 404
                probing = true
                val detail = it.detail
                val rawSources = detail?.list.orEmpty()
                    .map { src -> src.copy(linkList = src.linkList.filter { l -> l.link.isNotBlank() }) }
                    .filter { src -> src.linkList.isNotEmpty() }
                    .ifEmpty {
                        val pl = detail?.playList.orEmpty().filter { l -> l.link.isNotBlank() }
                        if (pl.isEmpty()) emptyList()
                        else listOf(PlaySource(id = "main", name = "默认线路", linkList = pl))
                    }
                val apiBase = GoFilmApp.instance.filmRepository.currentBaseUrl()
                val probeInput = rawSources.map { src ->
                    src.id to src.linkList.map { l -> l.link }
                }
                val result = GoFilmApp.instance.playUrlProber.probeFilm(
                    filmId = filmId,
                    apiBaseUrl = apiBase,
                    sources = probeInput
                )
                deadSourceIds = result.deadSourceIds
                deadLinks = result.deadLinks
                allDead = result.allDead
                // 若当前选中线路已失效，跳到第一条可用
                if (rawSources.isNotEmpty()) {
                    val alive = rawSources.indexOfFirst { it.id !in result.deadSourceIds }
                    if (alive >= 0) sourceIndex = alive
                }
                probing = false
            }
            .onFailure {
                error = it.message ?: "加载失败"
                loading = false
                probing = false
            }
    }

    when {
        loading -> LoadingBox()
        error != null && data == null -> Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            ErrorBox(error!!, onRetry = { refreshKey++ })
        }
        else -> {
            val detail = data?.detail
            val rawSources: List<PlaySource> = detail?.list.orEmpty()
                .map { src ->
                    src.copy(linkList = src.linkList.filter { it.link.isNotBlank() })
                }
                .filter { it.linkList.isNotEmpty() }
                .ifEmpty {
                    val pl = detail?.playList.orEmpty().filter { it.link.isNotBlank() }
                    if (pl.isEmpty()) emptyList()
                    else listOf(PlaySource(id = "main", name = "默认线路", linkList = pl))
                }
            // 隐去整条失效线路；集内隐去失效链接
            val sources: List<PlaySource> = rawSources
                .filter { it.id !in deadSourceIds }
                .map { src ->
                    src.copy(
                        linkList = src.linkList.filter { ep -> ep.link !in deadLinks }
                    )
                }
                .filter { it.linkList.isNotEmpty() }
            val safeSourceIndex = sourceIndex.coerceIn(0, (sources.size - 1).coerceAtLeast(0))
            val episodes = sources.getOrNull(safeSourceIndex)?.linkList.orEmpty()
            val safeEp = episodeIndex.coerceIn(0, (episodes.size - 1).coerceAtLeast(0))
            val playFrom = sources.getOrNull(safeSourceIndex)?.id
                ?: detail?.playFrom?.getOrNull(safeSourceIndex).orEmpty()
            val hasPlayable = sources.isNotEmpty() && !allDead
            val name = detail?.name.orEmpty().ifBlank { "未知影片" }
            val picture = detail?.picture.orEmpty()
            val remarks = detail?.remarks?.ifBlank { detail.descriptor?.remarks.orEmpty() }.orEmpty()
            val score = detail?.descriptor?.dbScore.orEmpty()
            val meta = listOf(
                detail?.cName?.ifBlank { detail.descriptor?.cName },
                detail?.year?.ifBlank { detail.descriptor?.year },
                detail?.area?.ifBlank { detail.descriptor?.area },
                remarks
            ).filter { !it.isNullOrBlank() }.joinToString(" · ")
            val intro = stripHtml(
                detail?.content?.ifBlank {
                    detail.blurb.ifBlank {
                        detail.descriptor?.content?.ifBlank { detail.descriptor?.blurb }.orEmpty()
                    }
                }.orEmpty()
            ).ifBlank { "暂无简介" }
            val director = detail?.director?.ifBlank { detail.descriptor?.director }.orEmpty().ifBlank { "—" }
            val actor = detail?.actor?.ifBlank { detail.descriptor?.actor }.orEmpty().ifBlank { "—" }

            Column(
                Modifier
                    .fillMaxSize()
                    .background(Bg)
                    .navigationBarsPadding()
            ) {
                // 顶部海报背景 + 信息卡
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                ) {
                    if (picture.isNotBlank()) {
                        AsyncImage(
                            model = picture,
                            contentDescription = name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.Black.copy(alpha = 0.45f),
                                        Color.Black.copy(alpha = 0.75f),
                                        Bg
                                    )
                                )
                            )
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                                tint = Color.White
                            )
                        }
                        IconButton(onClick = {
                            scope.launch {
                                GoFilmApp.instance.localRepository.toggleFavorite(
                                    filmId = filmId,
                                    name = name,
                                    picture = picture,
                                    remarks = remarks
                                )
                            }
                        }) {
                            Icon(
                                imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = "收藏",
                                tint = if (isFavorite) Accent else Color.White
                            )
                        }
                    }
                    Row(
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 8.dp)
                    ) {
                        Box(
                            Modifier
                                .width(104.dp)
                                .aspectRatio(0.72f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(BgCard)
                        ) {
                            if (picture.isNotBlank()) {
                                AsyncImage(
                                    model = picture,
                                    contentDescription = name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                name,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (score.isNotBlank() && score != "0.0") {
                                Text(
                                    "★ $score",
                                    color = Accent,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                            if (meta.isNotBlank()) {
                                Text(
                                    meta,
                                    color = Color.White.copy(alpha = 0.78f),
                                    fontSize = 12.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                            Text(
                                "导演：$director",
                                color = Color.White.copy(alpha = 0.65f),
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                            Text(
                                "主演：$actor",
                                color = Color.White.copy(alpha = 0.65f),
                                fontSize = 11.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 28.dp)
                ) {
                    if (probing) {
                        Text(
                            "正在检测播放地址…",
                            color = TextMuted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }

                    Button(
                        onClick = { onPlay(filmId, playFrom, safeEp) },
                        enabled = hasPlayable && !probing,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Accent,
                            contentColor = Color(0xFF1A1000),
                            disabledContainerColor = BgCard,
                            disabledContentColor = TextMuted
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp, bottom = 12.dp)
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when {
                                probing -> "检测中…"
                                hasPlayable -> {
                                    val epLabel = episodes.getOrNull(safeEp)?.episode.orEmpty()
                                    if (epLabel.isNotBlank()) "播放 $epLabel" else "立即播放"
                                }
                                else -> "暂不可播（链接失效）"
                            },
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    if (!hasPlayable && !probing) {
                        Text(
                            "已检测：播放地址全部失效（404/防盗链）。本片已标记为「失效」，列表中将隐藏或标注。请换一部试试。",
                            color = Color(0xFFFF8A80),
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                    } else if (deadSourceIds.isNotEmpty() && hasPlayable) {
                        Text(
                            "已隐藏 ${deadSourceIds.size} 条失效线路",
                            color = TextMuted,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    if (hasPlayable) {
                        Text(
                            "播放源 · ${sources.size} 条可用线路",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Spacer(Modifier.height(10.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            sources.forEachIndexed { index, source ->
                                val active = index == safeSourceIndex
                                val epCount = source.linkList.size
                                Text(
                                    "${source.name.ifBlank { "线路${index + 1}" }} ($epCount)",
                                    color = if (active) Accent else TextSecondary,
                                    fontSize = 12.sp,
                                    modifier = Modifier
                                        .background(
                                            if (active) AccentSoft else BgCard,
                                            RoundedCornerShape(10.dp)
                                        )
                                        .clickable {
                                            sourceIndex = index
                                            episodeIndex = 0
                                        }
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                        Text(
                            "选集 · 共 ${episodes.size} 集",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Spacer(Modifier.height(10.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            episodes.forEachIndexed { idx, ep ->
                                val active = idx == safeEp
                                Text(
                                    ep.episode.ifBlank { "${idx + 1}" },
                                    color = if (active) Accent else TextSecondary,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .background(
                                            if (active) AccentSoft else BgCard,
                                            RoundedCornerShape(10.dp)
                                        )
                                        .clickable {
                                            episodeIndex = idx
                                            onPlay(filmId, playFrom, idx)
                                        }
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }
                        }
                        if (remarks.isNotBlank() && episodes.size == 1 &&
                            (remarks.contains("集") || remarks.contains("全"))
                        ) {
                            Text(
                                "备注为「$remarks」，但当前线路仅 ${episodes.size} 集。片源可能未采全，可换线路或等待后台更新。",
                                color = TextMuted,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 10.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(18.dp))
                    Text("简介", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        intro,
                        color = TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 22.sp
                    )

                    val relate = data?.relate.orEmpty()
                        .filter { it.filmId > 0L && it.name.isNotBlank() }
                    if (relate.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        SectionTitle("相关推荐", modifier = Modifier.padding(horizontal = 0.dp))
                        FilmRow(relate) { onOpenDetail(it.filmId) }
                    }
                }
            }
        }
    }
}

private fun stripHtml(raw: String): String {
    if (raw.isBlank()) return ""
    return raw
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</p>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}
