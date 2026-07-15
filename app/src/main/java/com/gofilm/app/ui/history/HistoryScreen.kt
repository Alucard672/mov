package com.gofilm.app.ui.history

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.gofilm.app.data.local.WatchHistoryEntity
import com.gofilm.app.ui.components.HistoryListItem
import com.gofilm.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onContinue: (WatchHistoryEntity) -> Unit
) {
    val items by GoFilmApp.instance.localRepository.observeHistory()
        .collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(end = 8.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text("观看历史", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (items.isNotEmpty()) {
                TextButton(onClick = {
                    scope.launch { GoFilmApp.instance.localRepository.clearHistory() }
                }) {
                    Text("清空")
                }
            }
        }
        if (items.isEmpty()) {
            Text(
                "暂无观看记录\n播放影片后会自动保存在本机",
                color = TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(24.dp)
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(items, key = { it.filmId }) { item ->
                    HistoryListItem(
                        item = item,
                        onClick = { onContinue(item) },
                        onDelete = {
                            scope.launch {
                                GoFilmApp.instance.localRepository.deleteHistory(item.filmId)
                            }
                        }
                    )
                }
            }
        }
    }
}
