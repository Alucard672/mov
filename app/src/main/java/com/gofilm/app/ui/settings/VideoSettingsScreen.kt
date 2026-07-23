package com.gofilm.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gofilm.app.GoFilmApp
import com.gofilm.app.data.local.SettingsStore
import com.gofilm.app.ui.theme.Accent
import com.gofilm.app.ui.theme.AccentSoft
import com.gofilm.app.ui.theme.Bg
import com.gofilm.app.ui.theme.BgCard
import com.gofilm.app.ui.theme.TextMuted
import com.gofilm.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import kotlin.math.abs

private val SKIP_SEC_OPTIONS = listOf(0, 30, 60, 90, 120, 180, 300)
private val SPEED_OPTIONS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

private fun formatSkipLabel(sec: Int): String = when {
    sec <= 0 -> "关"
    sec < 60 -> "${sec}秒"
    sec % 60 == 0 -> "${sec / 60}分"
    else -> "${sec / 60}分${sec % 60}秒"
}

private fun formatSpeedLabel(s: Float): String =
    if (s == s.toLong().toFloat()) "${s.toLong()}x" else "${s}x"

/**
 * 视频播放相关全局设置：片头/片尾跳过、默认倍速。
 * 与「服务器设置」分离，从「我的」进入。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VideoSettingsScreen(onBack: () -> Unit) {
    val settings = GoFilmApp.instance.settingsStore
    val skipHead by settings.skipHeadSecFlow.collectAsState(SettingsStore.DEFAULT_SKIP_SEC)
    val skipTail by settings.skipTailSecFlow.collectAsState(SettingsStore.DEFAULT_SKIP_SEC)
    val speed by settings.playbackSpeedFlow.collectAsState(1f)
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp)
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        Text(
            "视频设置",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Text(
            "以下选项全局生效，播放页自动应用",
            color = TextMuted,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )

        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
        ) {
            Spacer(Modifier.height(12.dp))
            SectionTitle("默认倍速")
            SectionHint("新开播放时使用该倍速")
            ChipRow(
                options = SPEED_OPTIONS.map { it to formatSpeedLabel(it) },
                selected = { abs(speed - it) < 0.01f },
                onSelect = { sp -> scope.launch { settings.setPlaybackSpeed(sp) } }
            )

            Spacer(Modifier.height(22.dp))
            SectionTitle("跳过片头")
            SectionHint("开播自动跳过；选「关」关闭。默认 2 分钟")
            ChipRow(
                options = SKIP_SEC_OPTIONS.map { it to formatSkipLabel(it) },
                selected = { skipHead == it },
                onSelect = { sec -> scope.launch { settings.setSkipHeadSec(sec) } }
            )

            Spacer(Modifier.height(22.dp))
            SectionTitle("跳过片尾")
            SectionHint("接近片尾自动下一集；选「关」关闭。默认 2 分钟")
            ChipRow(
                options = SKIP_SEC_OPTIONS.map { it to formatSkipLabel(it) },
                selected = { skipTail == it },
                onSelect = { sec -> scope.launch { settings.setSkipTailSec(sec) } }
            )

            Spacer(Modifier.height(24.dp))
            Text(
                "手势说明",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            Text(
                "播放时（含横屏）：\n· 屏幕左侧上下滑 → 调节亮度\n· 屏幕右侧上下滑 → 调节音量\n· 中间区域 → 暂停/进度等播放控制",
                color = TextMuted,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
}

@Composable
private fun SectionHint(text: String) {
    Text(
        text,
        color = TextMuted,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(
    options: List<Pair<T, String>>,
    selected: (T) -> Boolean,
    onSelect: (T) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (value, label) ->
            val active = selected(value)
            Text(
                label,
                color = if (active) Accent else TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier
                    .background(
                        if (active) AccentSoft else BgCard,
                        RoundedCornerShape(10.dp)
                    )
                    .clickable { onSelect(value) }
                    .padding(horizontal = 14.dp, vertical = 9.dp)
            )
        }
    }
}
