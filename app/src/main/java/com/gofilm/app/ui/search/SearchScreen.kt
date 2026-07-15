package com.gofilm.app.ui.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gofilm.app.GoFilmApp
import com.gofilm.app.data.dto.FilmCard
import com.gofilm.app.ui.components.ErrorBox
import com.gofilm.app.ui.components.FilmGrid
import com.gofilm.app.ui.components.LoadingBox
import com.gofilm.app.ui.theme.Accent
import com.gofilm.app.ui.theme.BgCard
import com.gofilm.app.ui.theme.TextMuted
import com.gofilm.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenDetail: (Long) -> Unit
) {
    var keyword by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<FilmCard>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun doSearch() {
        val q = keyword.trim()
        if (q.isEmpty()) return
        scope.launch {
            loading = true
            error = null
            searched = true
            GoFilmApp.instance.filmRepository.search(q)
                .onSuccess {
                    results = it
                    loading = false
                }
                .onFailure {
                    error = it.message ?: "搜索失败"
                    loading = false
                }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("输入片名关键词", color = TextMuted) },
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = BgCard,
                    unfocusedContainerColor = BgCard,
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = BgCard
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { doSearch() })
            )
            TextButton(onClick = { doSearch() }) {
                Text("搜索", color = Accent)
            }
        }

        when {
            loading -> LoadingBox(Modifier.weight(1f))
            error != null -> ErrorBox(error!!, onRetry = { doSearch() }, modifier = Modifier.weight(1f))
            !searched -> Text(
                "输入关键词开始搜索",
                color = TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(24.dp)
            )
            results.isEmpty() -> Text(
                "没有找到相关影片",
                color = TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(24.dp)
            )
            else -> FilmGrid(results, onClick = { onOpenDetail(it.filmId) }, modifier = Modifier.weight(1f).fillMaxWidth())
        }
    }
}
