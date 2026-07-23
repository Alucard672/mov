package com.gofilm.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gofilm.app.BuildConfig
import com.gofilm.app.GoFilmApp
import com.gofilm.app.data.local.SettingsStore
import com.gofilm.app.ui.theme.Accent
import com.gofilm.app.ui.theme.AccentSoft
import com.gofilm.app.ui.theme.BgCard
import com.gofilm.app.ui.theme.TextMuted
import com.gofilm.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch

private val SKIP_SEC_OPTIONS = listOf(0, 30, 60, 90, 120, 180, 300)

private fun formatSkipLabel(sec: Int): String = when {
    sec <= 0 -> "关"
    sec < 60 -> "${sec}秒"
    sec % 60 == 0 -> "${sec / 60}分"
    else -> "${sec / 60}分${sec % 60}秒"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val repo = GoFilmApp.instance.filmRepository
    val settings = GoFilmApp.instance.settingsStore
    val saved by repo.baseUrlFlow.collectAsState(initial = BuildConfig.DEFAULT_BASE_URL)
    val skipHead by settings.skipHeadSecFlow.collectAsState(SettingsStore.DEFAULT_SKIP_SEC)
    val skipTail by settings.skipTailSecFlow.collectAsState(SettingsStore.DEFAULT_SKIP_SEC)
    var input by remember { mutableStateOf(saved) }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(saved) {
        if (input.isBlank() || input == BuildConfig.DEFAULT_BASE_URL) {
            input = saved
        }
    }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        Text(
            "设置",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(16.dp))
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
        ) {
            Text("服务器地址", color = TextMuted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("https://api.alucard.top/") },
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = BgCard,
                    unfocusedContainerColor = BgCard,
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = BgCard
                )
            )
            Text(
                "推荐：https://api.alucard.top/\n（根路径即片库接口，无需再写 /api/）",
                color = TextMuted,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 10.dp)
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    scope.launch {
                        testing = true
                        status = null
                        repo.setBaseUrl(input)
                        repo.testConnection()
                            .onSuccess {
                                status = "连接成功：${it.siteName.ifBlank { "GoFilm" }}"
                            }
                            .onFailure {
                                status = "连接失败：${it.message}"
                            }
                        testing = false
                    }
                },
                enabled = !testing,
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF1A1000)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(if (testing) "测试中…" else "保存并测试连接", fontWeight = FontWeight.SemiBold)
            }
            status?.let {
                Text(
                    it,
                    color = if (it.startsWith("连接成功")) Accent else Color(0xFFFF5C7A),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            Spacer(Modifier.height(28.dp))
            Text("播放 · 跳过片头（全局）", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(
                "默认 2 分钟，全片库生效；选「关」关闭",
                color = TextMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SKIP_SEC_OPTIONS.forEach { sec ->
                    val active = skipHead == sec
                    Text(
                        formatSkipLabel(sec),
                        color = if (active) Accent else TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .background(
                                if (active) AccentSoft else BgCard,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable { scope.launch { settings.setSkipHeadSec(sec) } }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Text("播放 · 跳过片尾（全局）", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(
                "默认 2 分钟，接近片尾自动下一集",
                color = TextMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SKIP_SEC_OPTIONS.forEach { sec ->
                    val active = skipTail == sec
                    Text(
                        formatSkipLabel(sec),
                        color = if (active) Accent else TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .background(
                                if (active) AccentSoft else BgCard,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable { scope.launch { settings.setSkipTailSec(sec) } }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            Text(
                "当前默认：${BuildConfig.DEFAULT_BASE_URL}",
                color = TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 20.dp)
            )
            Text(
                "App 版本：v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                color = TextMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
