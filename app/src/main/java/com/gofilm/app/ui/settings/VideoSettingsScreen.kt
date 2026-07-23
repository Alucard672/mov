package com.gofilm.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.Alignment
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
 * 视频播放全局设置：片头/片尾跳过、默认倍速。
 * 入口：我的 → 视频设置
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VideoSettingsScreen(onBack: () -> Unit) {
    val settings = GoFilmApp.instance.settingsStore
    val skipHead by settings.skipHeadSecFlow.collectAsState(initial = SettingsStore.DEFAULT_SKIP_SEC)
    val skipTail by settings.skipTailSecFlow.collectAsState(initial = SettingsStore.DEFAULT_SKIP_SEC)
    val speed by settings.playbackSpeedFlow.collectAsState(initial = 1f)
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Accent)
            }
            Text(
                "视频设置",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Accent
            )
        }

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 40.dp)
        ) {
            Text(
                "以下选项全局生效，所有影片播放时自动应用",
                color = TextMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // —— 跳过片头（优先展示）——
            SettingCard(
                title = "跳过片头",
                subtitle = "开播自动跳过设定时长。默认 2 分钟；选「关」则不跳过。"
            ) {
                IntChipRow(
                    options = SKIP_SEC_OPTIONS,
                    selected = skipHead,
                    labelOf = { formatSkipLabel(it) },
                    onSelect = { sec -> scope.launch { settings.setSkipHeadSec(sec) } }
                )
                Text(
                    "当前：${formatSkipLabel(skipHead)}",
                    color = Accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }

            Spacer(Modifier.height(14.dp))

            // —— 跳过片尾 ——
            SettingCard(
                title = "跳过片尾",
                subtitle = "播放到片尾前设定时长时自动下一集。默认 2 分钟；选「关」则播完本集。"
            ) {
                IntChipRow(
                    options = SKIP_SEC_OPTIONS,
                    selected = skipTail,
                    labelOf = { formatSkipLabel(it) },
                    onSelect = { sec -> scope.launch { settings.setSkipTailSec(sec) } }
                )
                Text(
                    "当前：${formatSkipLabel(skipTail)}",
                    color = Accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }

            Spacer(Modifier.height(14.dp))

            // —— 默认倍速 ——
            SettingCard(
                title = "默认倍速",
                subtitle = "打开播放页时使用该倍速"
            ) {
                FloatChipRow(
                    options = SPEED_OPTIONS,
                    selected = speed,
                    labelOf = { formatSpeedLabel(it) },
                    onSelect = { sp -> scope.launch { settings.setPlaybackSpeed(sp) } }
                )
                Text(
                    "当前：${formatSpeedLabel(speed)}",
                    color = Accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }

            Spacer(Modifier.height(14.dp))

            SettingCard(
                title = "手势说明",
                subtitle = null
            ) {
                Text(
                    "播放时（含横屏全屏）：\n" +
                        "· 屏幕左侧上下滑 → 调节亮度\n" +
                        "· 屏幕右侧上下滑 → 调节音量\n" +
                        "· 中间区域 → 暂停 / 进度条等控制",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
            }
        }
    }
}

@Composable
private fun SettingCard(
    title: String,
    subtitle: String?,
    content: @Composable () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(BgCard, RoundedCornerShape(14.dp))
            .border(1.dp, Accent.copy(alpha = 0.15f), RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Accent)
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                color = TextMuted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )
        } else {
            Spacer(Modifier.height(10.dp))
        }
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IntChipRow(
    options: List<Int>,
    selected: Int,
    labelOf: (Int) -> String,
    onSelect: (Int) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { value ->
            val active = selected == value
            Text(
                labelOf(value),
                color = if (active) Accent else TextSecondary,
                fontSize = 13.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .background(
                        if (active) AccentSoft else Bg.copy(alpha = 0.6f),
                        RoundedCornerShape(10.dp)
                    )
                    .border(
                        width = if (active) 1.dp else 0.dp,
                        color = if (active) Accent else Accent.copy(0f),
                        shape = RoundedCornerShape(10.dp)
                    )
                    .clickable { onSelect(value) }
                    .padding(horizontal = 14.dp, vertical = 9.dp)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FloatChipRow(
    options: List<Float>,
    selected: Float,
    labelOf: (Float) -> String,
    onSelect: (Float) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { value ->
            val active = abs(selected - value) < 0.01f
            Text(
                labelOf(value),
                color = if (active) Accent else TextSecondary,
                fontSize = 13.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .background(
                        if (active) AccentSoft else Bg.copy(alpha = 0.6f),
                        RoundedCornerShape(10.dp)
                    )
                    .border(
                        width = if (active) 1.dp else 0.dp,
                        color = if (active) Accent else Accent.copy(0f),
                        shape = RoundedCornerShape(10.dp)
                    )
                    .clickable { onSelect(value) }
                    .padding(horizontal = 14.dp, vertical = 9.dp)
            )
        }
    }
}
