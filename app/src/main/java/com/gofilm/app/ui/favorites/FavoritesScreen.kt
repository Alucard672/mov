package com.gofilm.app.ui.favorites

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gofilm.app.GoFilmApp
import com.gofilm.app.ui.components.FavoriteListItem
import com.gofilm.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
fun FavoritesScreen(
    onBack: () -> Unit,
    onOpenDetail: (Long) -> Unit
) {
    val items by GoFilmApp.instance.localRepository.observeFavorites()
        .collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text("我的收藏", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        if (items.isEmpty()) {
            Text(
                "暂无收藏\n在详情页点 ♡ 即可收藏到本机",
                color = TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(24.dp)
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(items, key = { it.filmId }) { item ->
                    FavoriteListItem(
                        item = item,
                        onClick = { onOpenDetail(item.filmId) },
                        onRemove = {
                            scope.launch {
                                GoFilmApp.instance.localRepository.removeFavorite(item.filmId)
                            }
                        }
                    )
                }
            }
        }
    }
}
