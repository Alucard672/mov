package com.gofilm.app.ui.classify

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gofilm.app.GoFilmApp
import com.gofilm.app.data.dto.CategoryItem
import com.gofilm.app.data.dto.FilmCard
import com.gofilm.app.ui.components.ErrorBox
import com.gofilm.app.ui.components.FilmPosterCard
import com.gofilm.app.ui.components.LoadingBox
import com.gofilm.app.ui.theme.Accent
import com.gofilm.app.ui.theme.AccentSoft
import com.gofilm.app.ui.theme.BgChip
import com.gofilm.app.ui.theme.TextMuted
import com.gofilm.app.ui.theme.TextSecondary
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun ClassifyScreen(onOpenDetail: (Long) -> Unit) {
    var topNav by remember { mutableStateOf<List<CategoryItem>>(emptyList()) }
    var subNav by remember { mutableStateOf<List<CategoryItem>>(emptyList()) }
    var selectedPid by remember { mutableLongStateOf(0L) }
    /** 0 = 全部；否则为二级分类 cid */
    var selectedCid by remember { mutableLongStateOf(0L) }

    var films by remember { mutableStateOf<List<FilmCard>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }
    var pageCount by remember { mutableIntStateOf(1) }
    var total by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableIntStateOf(0) }

    val gridState = rememberLazyGridState()
    val repo = GoFilmApp.instance.filmRepository

    LaunchedEffect(Unit) {
        repo.navCategory()
            .onSuccess { list ->
                topNav = list.filter { it.id > 0 && it.name.isNotBlank() }
                if (topNav.isNotEmpty() && selectedPid == 0L) {
                    selectedPid = topNav.first().id
                }
            }
            .onFailure {
                error = it.message ?: "分类加载失败"
                loading = false
            }
    }

    // 一级变化：刷新二级分类
    LaunchedEffect(selectedPid) {
        if (selectedPid <= 0L) return@LaunchedEffect
        subNav = emptyList()
        repo.filmClassify(selectedPid)
            .onSuccess { data ->
                subNav = data.title?.children.orEmpty()
                    .filter { it.id > 0 && it.name.isNotBlank() }
            }
    }

    // 列表加载：pid / cid / 手动重试
    LaunchedEffect(selectedPid, selectedCid, reloadToken) {
        if (selectedPid <= 0L) return@LaunchedEffect
        films = emptyList()
        page = 1
        pageCount = 1
        total = 0
        loading = true
        error = null
        loadingMore = false

        fetchPage(pid = selectedPid, cid = selectedCid, current = 1)
            .onSuccess { (list, pCount, tot) ->
                // 必须 distinct，后端偶发重复 id 会导致 LazyGrid key 冲突闪退
                films = list.filterPlayableCards().distinctBy { it.filmId }
                page = 1
                pageCount = pCount.coerceAtLeast(1)
                total = tot.coerceAtLeast(0)
                loading = false
            }
            .onFailure {
                error = it.message ?: "加载失败"
                loading = false
            }
    }

    val shouldLoadMore by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = info.totalItemsCount
            totalItems > 0 && last >= totalItems - 4
        }
    }

    LaunchedEffect(selectedPid, selectedCid, page, pageCount, loading, loadingMore) {
        snapshotFlow { shouldLoadMore }
            .distinctUntilChanged()
            .collect { nearEnd ->
                if (!nearEnd || loading || loadingMore || page >= pageCount || selectedPid <= 0L) {
                    return@collect
                }
                loadingMore = true
                val next = page + 1
                fetchPage(pid = selectedPid, cid = selectedCid, current = next)
                    .onSuccess { (list, pCount, tot) ->
                        films = (films + list.filterPlayableCards()).distinctBy { it.filmId }
                        page = next
                        pageCount = pCount
                        total = tot
                        loadingMore = false
                    }
                    .onFailure {
                        loadingMore = false
                    }
            }
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            "分类",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
        )

        if (topNav.isNotEmpty()) {
            ChipRow(
                items = topNav.map { it.id to it.name },
                selectedId = selectedPid,
                onSelect = { id ->
                    if (id != selectedPid) {
                        selectedPid = id
                        selectedCid = 0L
                    }
                }
            )
        }

        if (subNav.isNotEmpty()) {
            ChipRow(
                items = listOf(0L to "全部") + subNav.map { it.id to it.name },
                selectedId = selectedCid,
                onSelect = { selectedCid = it },
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        if (total > 0 && !loading) {
            Text(
                "共 $total 部 · 已加载 ${films.size} 部",
                color = TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        when {
            loading && films.isEmpty() -> LoadingBox(Modifier.weight(1f))
            error != null && films.isEmpty() -> ErrorBox(
                error!!,
                onRetry = { reloadToken++ },
                modifier = Modifier.weight(1f)
            )
            films.isEmpty() -> Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text("该分类暂无影片", color = TextSecondary)
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    state = gridState,
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(
                        items = films,
                        // 仅用 filmId；名称可能重复，且 id 已在入库时 distinct
                        key = { film -> film.filmId }
                    ) { film ->
                        FilmPosterCard(
                            film = film,
                            onClick = { onOpenDetail(film.filmId) },
                            modifier = Modifier.fillMaxWidth(),
                            width = 120
                        )
                    }
                    if (loadingMore) {
                        item(span = { GridItemSpan(3) }) {
                            Box(
                                Modifier.fillMaxWidth().padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = Accent)
                            }
                        }
                    } else if (page >= pageCount && films.isNotEmpty()) {
                        item(span = { GridItemSpan(3) }) {
                            Text(
                                "已全部加载",
                                color = TextMuted,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChipRow(
    items: List<Pair<Long, String>>,
    selectedId: Long,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.forEach { (id, name) ->
            val active = id == selectedId
            Text(
                name,
                color = if (active) Accent else TextSecondary,
                fontSize = 13.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .background(
                        if (active) AccentSoft else BgChip,
                        RoundedCornerShape(999.dp)
                    )
                    .clickable { onSelect(id) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}

private fun List<FilmCard>.filterPlayableCards(): List<FilmCard> =
    filter { it.filmId > 0L && it.name.isNotBlank() }
        .distinctBy { it.filmId }

private suspend fun fetchPage(
    pid: Long,
    cid: Long,
    current: Int
): Result<Triple<List<FilmCard>, Int, Int>> {
    val cat = if (cid > 0L) cid.toString() else ""
    val repo = GoFilmApp.instance.filmRepository
    val search = repo.filmClassifySearch(pid = pid, categoryCid = cat, current = current)
    if (search.isSuccess) {
        val data = search.getOrThrow()
        val pCount = (data.page?.pageCount ?: 1).coerceAtLeast(1)
        val tot = data.page?.total ?: data.list.size
        return Result.success(Triple(data.list, pCount, tot))
    }
    // 仅第一页可回退到分类首页块
    if (current == 1) {
        return repo.filmClassify(pid).map { c ->
            val merged = (
                c.content?.recent.orEmpty() +
                    c.content?.news.orEmpty() +
                    c.content?.top.orEmpty()
                ).distinctBy { it.filmId }
            Triple(merged, 1, merged.size)
        }
    }
    return Result.failure(search.exceptionOrNull() ?: Exception("加载失败"))
}
