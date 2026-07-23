package com.gofilm.app.ui.mine

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gofilm.app.BuildConfig
import com.gofilm.app.GoFilmApp
import com.gofilm.app.R
import com.gofilm.app.data.local.TorrentMenuUnlock
import com.gofilm.app.data.repo.AppUpdateChecker
import com.gofilm.app.data.repo.AppUpdateInfo
import com.gofilm.app.data.repo.UpdateCheckResult
import com.gofilm.app.data.torrent.TorrentDownloadStore
import com.gofilm.app.data.torrent.TorrentEngine
import com.gofilm.app.ui.theme.Accent
import com.gofilm.app.ui.theme.BgCard
import com.gofilm.app.ui.theme.Primary
import com.gofilm.app.ui.theme.TextMuted
import com.gofilm.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch

private const val TORRENT_UNLOCK_TAPS = 7

@Composable
fun MineScreen(
    onOpenHistory: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenTorrent: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onOpenLinkDownload: () -> Unit = {},
    onOpenAdCoop: () -> Unit = {},
    onOpenVideoSettings: () -> Unit = {},
    onOpenServerSettings: () -> Unit = {}
) {
    val history by GoFilmApp.instance.localRepository.observeHistory().collectAsState(initial = emptyList())
    val favorites by GoFilmApp.instance.localRepository.observeFavorites().collectAsState(initial = emptyList())
    val torrentRecords by TorrentDownloadStore.records.collectAsState()
    val activeTorrentCount = torrentRecords.count { it.isActive || (!it.isFinished && it.state == "DOWNLOADING") }
    val finishedCount = torrentRecords.count { it.isFinished && it.state != "MISSING" }
    val versionLabel = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    // 每次进入「我的」都从磁盘读一次，避免 remember 初始值丢失
    var torrentMenuVisible by remember {
        mutableStateOf(TorrentMenuUnlock.isUnlocked(context))
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        torrentMenuVisible = TorrentMenuUnlock.isUnlocked(context)
    }
    var logoTapCount by remember { mutableIntStateOf(0) }
    var lastLogoTapAt by remember { mutableStateOf(0L) }

    fun onLogoTap() {
        if (torrentMenuVisible) return
        val now = System.currentTimeMillis()
        // 超过 3 秒未连点则重新计数
        if (now - lastLogoTapAt > 3000L) logoTapCount = 0
        lastLogoTapAt = now
        logoTapCount++
        if (logoTapCount >= TORRENT_UNLOCK_TAPS) {
            TorrentMenuUnlock.unlock(context)
            torrentMenuVisible = true
            logoTapCount = 0
            Toast.makeText(context, "已解锁种子下载（下次打开无需再点）", Toast.LENGTH_SHORT).show()
        }
    }

    var updateStatus by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var pendingUpdate by remember { mutableStateOf<AppUpdateInfo?>(null) }
    /** 有新版本时「系统更新」菜单显示红点 */
    var hasUpdate by remember { mutableStateOf(false) }
    var latestVersionName by remember { mutableStateOf<String?>(null) }

    // 进入「我的」静默检查是否有新版本（只为红点，不弹窗）
    androidx.compose.runtime.LaunchedEffect(Unit) {
        when (val r = AppUpdateChecker.check()) {
            is UpdateCheckResult.Available -> {
                hasUpdate = true
                latestVersionName = r.info.versionName
            }
            else -> {
                hasUpdate = false
                latestVersionName = null
            }
        }
    }

    fun startDownload(info: AppUpdateInfo) {
        scope.launch {
            downloading = true
            progress = 0
            updateStatus = "正在下载 v${info.versionName}…"
            val result = AppUpdateChecker.downloadApk(context, info) { p -> progress = p }
            downloading = false
            result
                .onSuccess { file ->
                    if (!AppUpdateChecker.canRequestInstall(context)) {
                        updateStatus = "请允许「安装未知应用」后重试"
                        activity?.let { AppUpdateChecker.openInstallPermissionSettings(it) }
                        return@onSuccess
                    }
                    updateStatus = "下载完成，正在打开安装…"
                    AppUpdateChecker.installApk(context, file)
                }
                .onFailure {
                    updateStatus = "下载失败：${it.message ?: "未知错误"}"
                }
        }
    }

    fun checkUpdate() {
        if (checking || downloading) return
        scope.launch {
            checking = true
            updateStatus = "正在检查更新…"
            when (val r = AppUpdateChecker.check()) {
                is UpdateCheckResult.AlreadyLatest -> {
                    updateStatus = "已是最新版 $versionLabel"
                    pendingUpdate = null
                    hasUpdate = false
                    latestVersionName = null
                }
                is UpdateCheckResult.Available -> {
                    updateStatus = "发现新版本 v${r.info.versionName}"
                    pendingUpdate = r.info
                    hasUpdate = true
                    latestVersionName = r.info.versionName
                }
                is UpdateCheckResult.Failed -> {
                    updateStatus = "检查失败：${r.message}"
                    pendingUpdate = null
                }
            }
            checking = false
        }
    }

    pendingUpdate?.let { info ->
        AlertDialog(
            onDismissRequest = {
                // 强制更新不可关闭
                if (!downloading && !info.isForce) pendingUpdate = null
            },
            title = {
                Text(
                    if (info.isForce) "必须更新 v${info.versionName}"
                    else "发现新版本 v${info.versionName}"
                )
            },
            text = {
                Column {
                    if (info.isForce) {
                        Text(
                            "当前版本过旧，请更新后继续使用。",
                            fontSize = 13.sp,
                            color = Color(0xFFFF8A80)
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(
                        info.changelog.ifBlank { "修复问题并优化体验，建议更新到最新版。" },
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                    if (downloading) {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = Accent
                        )
                        Text(
                            "下载中 $progress%",
                            fontSize = 12.sp,
                            color = TextMuted,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { startDownload(info) },
                    enabled = !downloading
                ) {
                    Text(
                        if (downloading) "下载中…"
                        else if (info.isForce) "立即更新"
                        else "立即更新",
                        color = Accent
                    )
                }
            },
            dismissButton = if (info.isForce) {
                {}
            } else {
                {
                    TextButton(
                        onClick = { pendingUpdate = null },
                        enabled = !downloading
                    ) {
                        Text("稍后", color = TextMuted)
                    }
                }
            }
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("我的", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.linearGradient(listOf(Primary.copy(0.25f), Accent.copy(0.12f))))
                .clickable { onLogoTap() }
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = "Alucard影视",
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Crop
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Alucard影视", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (torrentMenuVisible) "影视点播 · 种子下载 · 本地收藏"
                    else "影视点播 · 本地收藏",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
                Text(
                    "当前版本 $versionLabel",
                    color = Accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        MenuItem(
            Icons.Default.History,
            "观看历史",
            if (history.isEmpty()) "暂无记录" else "共 ${history.size} 条",
            onOpenHistory
        )
        MenuItem(
            Icons.Default.Favorite,
            "我的收藏",
            if (favorites.isEmpty()) "暂无收藏" else "共 ${favorites.size} 部",
            onOpenFavorites
        )
        MenuItem(
            Icons.Default.Download,
            "我的下载",
            if (finishedCount > 0) "已完成 $finishedCount 个 · 本地可播"
            else "种子/磁力/链接下载在此查看",
            onOpenDownloads
        )
        MenuItem(
            Icons.Default.Link,
            "链接下载",
            "抖音/B站/知乎等 · 直链 / yt-dlp",
            onOpenLinkDownload
        )
        if (torrentMenuVisible) {
            MenuItem(
                Icons.Default.PlayCircle,
                "种子下载",
                when {
                    TorrentEngine.hasActiveDownload() || activeTorrentCount > 0 ->
                        "下载中 $activeTorrentCount · 共 ${torrentRecords.size} 条记录"
                    torrentRecords.isNotEmpty() ->
                        "共 ${torrentRecords.size} 条下载记录"
                    else -> "种子 / 磁力 · 返回不中断"
                },
                onOpenTorrent
            )
        }
        MenuItem(
            icon = Icons.Default.Speed,
            title = "视频设置",
            subtitle = "跳过片头 · 跳过片尾 · 默认倍速",
            onClick = {
                try {
                    onOpenVideoSettings()
                } catch (t: Throwable) {
                    Toast.makeText(context, "打开失败：${t.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
        MenuItem(
            icon = Icons.Default.Settings,
            title = "服务器设置",
            subtitle = "API 地址与连接测试",
            onClick = {
                try {
                    onOpenServerSettings()
                } catch (t: Throwable) {
                    Toast.makeText(context, "打开失败：${t.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
        MenuItem(
            icon = Icons.Default.SystemUpdate,
            title = "系统更新",
            subtitle = when {
                checking -> "检查中…"
                downloading -> "下载中 $progress%"
                hasUpdate -> "有新版本 v${latestVersionName.orEmpty().ifBlank { "…" }}，点击更新"
                else -> "获取最新版本"
            },
            onClick = { checkUpdate() },
            showBadge = hasUpdate && !downloading
        )
        MenuItem(
            Icons.Default.Info,
            "关于",
            "Alucard影视 $versionLabel",
            {}
        )
        // 广告合作：放在列表末尾，页面可滚动，避免被底栏挡住
        MenuItem(
            Icons.Default.Campaign,
            "广告合作说明",
            "微信/手机 15989049527 · 开屏/Banner",
            onOpenAdCoop
        )
        updateStatus?.let { msg ->
            Text(
                msg,
                color = if (msg.contains("失败") || msg.contains("请允许")) {
                    androidx.compose.ui.graphics.Color(0xFFFF8A80)
                } else {
                    Accent
                },
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "Alucard影视 $versionLabel",
            color = TextMuted,
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 4.dp)
        )
        Text(
            "分发下载：${com.gofilm.app.BuildConfig.DOWNLOAD_URL}",
            color = TextMuted,
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 16.dp)
        )
    }
}

@Composable
private fun MenuItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    showBadge: Boolean = false
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(BgCard)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(BgCard),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = Accent)
            if (showBadge) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 2.dp, end = 2.dp)
                        .size(9.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(Color(0xFFFF3B30))
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                if (showBadge) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .background(Color(0xFFFF3B30))
                    )
                }
            }
            Text(subtitle, color = TextMuted, fontSize = 12.sp)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = TextMuted)
    }
}
