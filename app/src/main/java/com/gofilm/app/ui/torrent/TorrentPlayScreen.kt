package com.gofilm.app.ui.torrent

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.PlayerView
import com.gofilm.app.data.torrent.TorrentBencode
import com.gofilm.app.data.torrent.TorrentDownloadRecord
import com.gofilm.app.data.torrent.TorrentDownloadStore
import com.gofilm.app.data.torrent.TorrentEngine
import com.gofilm.app.data.torrent.TorrentFileItem
import com.gofilm.app.data.torrent.TorrentNetwork
import com.gofilm.app.ui.theme.Accent
import com.gofilm.app.ui.theme.Bg
import com.gofilm.app.ui.theme.BgCard
import com.gofilm.app.ui.theme.TextMuted
import com.gofilm.app.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 种子 / 磁力双 TAB：
 * 1. 种子：选择 .torrent → 点视频 → 边下边播
 * 2. 磁力：粘贴 magnet → 解析元数据 → 点视频 → 边下边播
 * 完成后保存到本机「我的下载」目录。
 */
@OptIn(UnstableApi::class)
@Composable
fun TorrentPlayScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val records by TorrentDownloadStore.records.collectAsState()

    fun hideKeyboard() {
        try {
            focusManager.clearFocus(force = true)
        } catch (_: Throwable) {
        }
        try {
            keyboardController?.hide()
        } catch (_: Throwable) {
        }
        // 系统级兜底：部分机型仅 clearFocus 键盘不收
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            val activity = context as? Activity
            val token = activity?.currentFocus?.windowToken
                ?: activity?.window?.decorView?.windowToken
            if (imm != null && token != null) {
                imm.hideSoftInputFromWindow(token, 0)
            }
        } catch (_: Throwable) {
        }
    }

    var tabIndex by remember { mutableIntStateOf(0) } // 0 种子 1 磁力
    var torrentBytes by remember { mutableStateOf<ByteArray?>(null) }
    var name by remember { mutableStateOf("") }
    var files by remember { mutableStateOf<List<TorrentFileItem>>(emptyList()) }
    var totalSize by remember { mutableStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var magnetInput by remember { mutableStateOf("") }
    /** 磁力原始链接；下载时与种子 TAB 共用同一 startDownload 路径 */

    var downloading by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<TorrentFileItem?>(null) }
    var progress by remember { mutableStateOf<TorrentEngine.Progress?>(null) }
    var playFile by remember { mutableStateOf<File?>(null) }
    var statusHint by remember { mutableStateOf<String?>(null) }
    var needPlayRetry by remember { mutableStateOf(false) }
    var lastPlayTryDoneBytes by remember { mutableStateOf(0L) }
    var isPlayingOk by remember { mutableStateOf(false) }
    var playFailCount by remember { mutableStateOf(0) }
    /** true=点文件边下边播；false=仅继续下载，不进播放器/文件列表 */
    var wantProgressivePlay by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        TorrentDownloadStore.init(context)
        if (TorrentEngine.hasActiveDownload() || !TorrentEngine.isIdle()) {
            downloading = true
            progress = TorrentEngine.snapshot()
            // 恢复后台任务时只跟进度，不自动打开播放器
            wantProgressivePlay = false
            playFile = null
            statusHint = "后台下载中，已恢复进度"
        }
    }

    val player = remember {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(5_000, 30_000, 1_500, 3_000)
            .build()
        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()
            .apply {
                playWhenReady = true
                repeatMode = Player.REPEAT_MODE_OFF
            }
    }

    val mediaSourceFactory = remember {
        // 边下边播：CBR 估算时长，尽量在文件不完整时也能解封装
        val extractors = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setConstantBitrateSeekingAlwaysEnabled(true)
        ProgressiveMediaSource.Factory(
            DefaultDataSource.Factory(context, FileDataSource.Factory()),
            extractors
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                player.release()
            } catch (_: Throwable) {
            }
        }
    }

    LaunchedEffect(downloading, wantProgressivePlay) {
        if (!downloading) return@LaunchedEffect
        var hasPrepared = false
        var lastRetryAtMs = 0L
        while (isActive && downloading) {
            val p = TorrentEngine.snapshot()
            progress = p
            if (p.error != null && p.state == "ERROR") {
                error = p.error
                statusHint = "下载出错: ${p.error}"
            } else if (p.state != "IDLE") {
                val pct = (p.fileProgress * 100).toInt().coerceIn(0, 100)
                val base = buildString {
                    append(stateLabel(p.state))
                    append(" · ")
                    append(formatSpeed(p.downloadRate))
                    append(" · ")
                    append(formatSize(p.fileDoneBytes))
                    append("/")
                    append(formatSize(p.fileTotalBytes))
                    append(" ($pct%)")
                    append(" · 同伴 ${p.numPeers}")
                    if (wantProgressivePlay && !p.isFinished) append(" · 边下边播")
                    else if (p.isFinished) append(" · 已保存到我的下载")
                    else append(" · 后台下载")
                }

                // 仅「点文件开始」才自动边下边播；继续下载只刷速度/进度
                if (!wantProgressivePlay) {
                    statusHint = base
                    delay(1000)
                    continue
                }

                val f = TorrentEngine.currentFile()
                val done = p.fileDoneBytes
                val ready = TorrentEngine.isPlayable(minBytes = 4L * 1024 * 1024)
                val now = System.currentTimeMillis()
                // 片头连续 piece 增加，或又多下了 ≥2MB，再重试开播
                val canRetry = needPlayRetry &&
                    (
                        (p.headHave >= p.headNeed && done >= 2L * 1024 * 1024) ||
                            done >= lastPlayTryDoneBytes + 2L * 1024 * 1024
                        ) &&
                    now - lastRetryAtMs >= 4_000L
                val shouldTry = f != null && f.exists() && ready &&
                    (!hasPrepared || canRetry) &&
                    !isPlayingOk

                statusHint = when {
                    isPlayingOk -> base
                    !p.headReady && p.fileDoneBytes > 0 ->
                        "$base\n片头连续 ${p.headHave}/${p.headNeed} 块，正在优先下片头以便播放…"
                    needPlayRetry && pct >= 35 ->
                        "$base\n部分 MP4/H265 索引在文件尾，需下更多（当前 $pct%）…"
                    needPlayRetry ->
                        "$base\n片头未齐，正在重下片头后重试播放…"
                    p.fileDoneBytes > 0 && !ready ->
                        "$base\n已下 ${formatSize(done)}，等片头约 4MB 连续数据后开播…"
                    else -> base
                }

                // 片头不够时主动让引擎抢片头
                if (!isPlayingOk && !p.headReady && p.numPeers > 0) {
                    withContext(Dispatchers.IO) {
                        TorrentEngine.requestHeadBoost()
                    }
                }

                if (shouldTry) {
                    playFile = f
                    lastPlayTryDoneBytes = done
                    lastRetryAtMs = now
                    needPlayRetry = false
                    try {
                        // 每次重试重建 MediaSource，避免半截文件状态残留
                        try {
                            player.stop()
                            player.clearMediaItems()
                        } catch (_: Throwable) {
                        }
                        val item = MediaItem.fromUri(Uri.fromFile(f!!))
                        val source = mediaSourceFactory.createMediaSource(item)
                        player.setMediaSource(source)
                        player.prepare()
                        player.playWhenReady = true
                        player.play()
                        hasPrepared = true
                        error = null
                    } catch (_: Throwable) {
                        needPlayRetry = true
                        hasPrepared = false
                        isPlayingOk = false
                        playFailCount++
                        withContext(Dispatchers.IO) {
                            TorrentEngine.requestHeadBoost()
                        }
                    }
                }
            }
            delay(1200)
        }
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        loading = true
        error = null
        statusHint = null
        downloading = false
        selected = null
        playFile = null
        progress = null
        try {
            player.stop()
            player.clearMediaItems()
        } catch (_: Throwable) {
        }
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
                if (bytes == null || bytes.isEmpty()) {
                    error = "无法读取种子文件"
                    files = emptyList()
                    name = ""
                    torrentBytes = null
                } else {
                    val parsed = withContext(Dispatchers.Default) {
                        TorrentBencode.parse(bytes)
                    }
                    torrentBytes = bytes
                    name = parsed.name
                    files = parsed.files
                    totalSize = parsed.totalSize
                    statusHint = "请点选下方视频文件，开始边下边播"
                    error = null
                }
            } catch (t: Throwable) {
                error = t.message ?: "解析种子失败"
                files = emptyList()
                name = ""
                torrentBytes = null
            } finally {
                loading = false
            }
        }
    }

    fun parseMagnet() {
        hideKeyboard()
        val raw = magnetInput.trim()
        if (raw.isBlank()) {
            error = "请粘贴磁力链接"
            return
        }
        loading = true
        error = null
        statusHint = "开始解析…"
        downloading = false
        selected = null
        playFile = null
        progress = null
        files = emptyList()
        name = ""
        torrentBytes = null
        try {
            player.stop()
            player.clearMediaItems()
        } catch (_: Throwable) {
        }
        scope.launch {
            // 实时刷新解析进度（引擎侧 StateFlow）
            val statusJob = launch {
                TorrentEngine.magnetStatus.collect { msg ->
                    if (msg.isNotBlank() && loading) statusHint = msg
                }
            }
            try {
                val bytes = withContext(Dispatchers.IO) {
                    TorrentEngine.fetchMagnetMetadata(context, raw, timeoutSec = 25)
                }
                val parsed = withContext(Dispatchers.Default) {
                    TorrentBencode.parse(bytes)
                }
                torrentBytes = bytes
                name = parsed.name
                files = parsed.files
                totalSize = parsed.totalSize
                statusHint = "解析成功 · ${parsed.files.size} 个文件 · 点选后与本地种子同一方式下载"
                error = null
            } catch (t: Throwable) {
                error = t.message ?: "磁力解析失败"
                files = emptyList()
                name = ""
                torrentBytes = null
                statusHint = null
            } finally {
                statusJob.cancel()
                loading = false
            }
        }
    }

    fun openLocalFile(path: String) {
        val f = File(path)
        if (!f.exists()) {
            error = "文件不存在或尚未写入"
            return
        }
        playFile = f
        needPlayRetry = false
        isPlayingOk = false
        try {
            val item = MediaItem.fromUri(Uri.fromFile(f))
            val source = mediaSourceFactory.createMediaSource(item)
            player.setMediaSource(source)
            player.prepare()
            player.playWhenReady = true
            player.play()
            statusHint = "正在播放本地文件"
        } catch (t: Throwable) {
            error = "无法播放：${t.message}"
        }
    }

    fun startPlay(file: TorrentFileItem) {
        val bytes = torrentBytes
        if (bytes == null) {
            error = if (tabIndex == 1) "请先解析磁力链接" else "请先选择种子文件"
            return
        }
        selected = file
        downloading = false
        wantProgressivePlay = true
        playFile = null
        needPlayRetry = false
        lastPlayTryDoneBytes = 0L
        isPlayingOk = false
        playFailCount = 0
        error = null
        statusHint = if (tabIndex == 1) {
            "磁力已转成种子，按本地种子方式启动下载…"
        } else {
            "正在启动下载引擎…"
        }
        progress = null
        try {
            player.stop()
            player.clearMediaItems()
        } catch (_: Throwable) {
        }
        scope.launch {
            try {
                // 磁力 TAB 与种子 TAB 走完全相同的 startDownload（同一套 libtorrent 加种逻辑）
                val out = withContext(Dispatchers.IO) {
                    if (tabIndex == 1) {
                        TorrentEngine.startMagnetDownload(
                            context = context,
                            torrentBytes = bytes,
                            fileIndex = file.index,
                            magnetUri = magnetInput
                        )
                    } else {
                        TorrentEngine.startDownload(context, bytes, file.index)
                    }
                }
                downloading = true
                val snap = TorrentEngine.snapshot()
                if (snap.isFinished) {
                    statusHint = "已下载完成，直接播放（未重复下载）"
                    openLocalFile(out.absolutePath)
                } else {
                    statusHint = "下载中 · 与本地种子同一引擎 · 完成后进「我的下载」"
                }
            } catch (t: Throwable) {
                downloading = false
                wantProgressivePlay = false
                error = t.message ?: "启动下载失败"
                statusHint = null
            }
        }
    }

    BackHandler {
        try {
            player.pause()
        } catch (_: Throwable) {
        }
        onBack()
    }

    fun stopDownload() {
        scope.launch {
            withContext(Dispatchers.IO) {
                TorrentEngine.stopCurrent(removeData = false)
            }
            downloading = false
            statusHint = "已停止下载（文件保留，可在「我的下载」查看完成项）"
            progress = TorrentEngine.snapshot()
        }
    }

    val tabs = listOf("种子", "磁力链接")
    val recent = records.filter { !it.isFinished }.take(8)

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                hideKeyboard()
                try {
                    player.pause()
                } catch (_: Throwable) {
                }
                onBack()
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                "种子下载",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.weight(1f)
            )
        }

        TabRow(
            selectedTabIndex = tabIndex,
            containerColor = Bg,
            contentColor = Accent,
            indicator = { positions ->
                if (tabIndex < positions.size) {
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(positions[tabIndex]),
                        color = Accent
                    )
                }
            }
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = tabIndex == index,
                    onClick = {
                        hideKeyboard()
                        if (tabIndex != index) {
                            tabIndex = index
                            if (!downloading) {
                                torrentBytes = null
                                files = emptyList()
                                name = ""
                                selected = null
                                error = null
                                statusHint = null
                            }
                        }
                    },
                    text = {
                        Text(
                            title,
                            fontWeight = if (tabIndex == index) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    selectedContentColor = Accent,
                    unselectedContentColor = TextMuted
                )
            }
        }

        // 播放器：仅边下边播 / 手动播放时显示；「继续下载」不展开黑屏区域
        if (playFile != null || (downloading && wantProgressivePlay)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black)
            ) {
                if (playFile != null) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                this.player = player
                                layoutParams = FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                useController = true
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        update = { it.player = player }
                    )
                } else {
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("缓冲中，请稍候…", color = Color.White, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { progress?.fileProgress ?: 0f },
                            modifier = Modifier
                                .fillMaxWidth(0.6f)
                                .height(4.dp),
                            color = Accent,
                            trackColor = Color.DarkGray
                        )
                    }
                }
            }
        }

        // 下方全部可滚动（修复：原先中间 Column 无滚动 + 全页 clickable 抢手势）
        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)
        ) {
            item(key = "controls") {
                Column {
                    if (tabIndex == 0) {
                        Text(
                            "选择本地 .torrent → 点视频文件 → 边下边播。\n完成后自动保存到手机本地「我的下载」。",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                hideKeyboard()
                                picker.launch(arrayOf("application/x-bittorrent", "*/*"))
                            },
                            enabled = !loading,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Accent,
                                contentColor = Color(0xFF1A1000)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (loading) "解析中…" else "选择本地种子文件",
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    } else {
                        Text(
                            "粘贴 magnet → 解析 → 点视频下载。\n完成后保存到「我的下载」。若长期 0 同伴，请关闭代理/VPN 后重试。",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = magnetInput,
                            onValueChange = { magnetInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                Text("magnet:?xt=urn:btih:…", color = TextMuted, fontSize = 13.sp)
                            },
                            minLines = 3,
                            maxLines = 5,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Accent,
                                unfocusedBorderColor = BgCard,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = Accent
                            ),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    hideKeyboard()
                                    parseMagnet()
                                }
                            )
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    val text = clipboard.getText()?.text.orEmpty()
                                    if (text.isNotBlank()) {
                                        magnetInput = text.trim()
                                        statusHint = "已从剪贴板粘贴"
                                        error = null
                                    } else {
                                        error = "剪贴板为空"
                                    }
                                    hideKeyboard()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = BgCard,
                                    contentColor = Accent
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.ContentPaste, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("粘贴", fontWeight = FontWeight.SemiBold)
                            }
                            Button(
                                onClick = {
                                    hideKeyboard()
                                    parseMagnet()
                                },
                                enabled = !loading && magnetInput.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Accent,
                                    contentColor = Color(0xFF1A1000)
                                ),
                                modifier = Modifier
                                    .weight(1.4f)
                                    .height(46.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    if (loading) "解析中…" else "解析磁力",
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    if (downloading && !TorrentEngine.isIdle()) {
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { stopDownload() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF3A2020),
                                contentColor = Color(0xFFFF8A80)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(42.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("停止当前下载", fontWeight = FontWeight.SemiBold)
                        }
                    }

                    if (name.isNotBlank()) {
                        Text(
                            name,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            modifier = Modifier.padding(top = 14.dp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "共 ${files.size} 个文件 · ${formatSize(totalSize)} · 视频 ${files.count { it.isVideo }} 个",
                            color = TextMuted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    progress?.let { p ->
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { p.fileProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                            color = Accent,
                            trackColor = BgCard
                        )
                        Text(
                            "已下 ${formatSize(p.fileDoneBytes)} / ${formatSize(p.fileTotalBytes)} " +
                                "(${(p.fileProgress * 100).toInt()}%) · ${formatSpeed(p.downloadRate)}",
                            color = TextMuted,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Text(
                            "已连接 ${p.numPeers} · 候选 ${p.listPeers}/${p.connectCandidates} · " +
                                "DHT ${p.dhtNodes}" +
                                if (p.currentTracker.isNotBlank()) " · Tracker 有响应" else " · 等 Tracker",
                            color = TextMuted,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        Text(
                            "片头连续 ${p.headHave}/${p.headNeed} 块" +
                                if (p.headReady) " · 可尝试开播" else " · 优先下片头中",
                            color = if (p.headReady) Accent else TextMuted,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        p.networkHint?.let { hint ->
                            Text(
                                hint,
                                color = Color(0xFFFF8A80),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                        if (!p.isFinished && p.downloadRate <= 0 && p.fileDoneBytes <= 0L) {
                            val msg = when {
                                p.networkHint != null -> null
                                p.numPeers > 0 ->
                                    "已连上 ${p.numPeers} 个同伴但暂无数据：对方可能没有片头。" +
                                        "正在改下任意可用分片，请再等 15–30 秒…"
                                p.listPeers > 0 || p.connectCandidates > 0 ->
                                    "已找到 ${p.listPeers.coerceAtLeast(p.connectCandidates)} 个候选，连接中…"
                                p.dhtNodes > 0 ->
                                    "DHT 已通，正在找同伴…"
                                else ->
                                    "正在找源…"
                            }
                            if (msg != null) {
                                Text(
                                    msg,
                                    color = Color(0xFFFFB74D),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                        }
                    }

                    statusHint?.let {
                        Text(
                            it,
                            color = Accent,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    error?.let {
                        Text(
                            it,
                            color = Color(0xFFFF8A80),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }

            if (recent.isNotEmpty()) {
                item(key = "recent_title") {
                    Text(
                        "进行中 / 未完成",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                }
                items(recent, key = { "rec_${it.id}" }) { rec ->
                    DownloadRecordRow(
                        rec = rec,
                        onPlay = { openLocalFile(rec.filePath) },
                        onResume = {
                            if (!rec.isFinished && !rec.isActive) {
                                scope.launch {
                                    try {
                                        error = null
                                        // 继续下载：只恢复任务，不进播放器、不展开种子文件列表
                                        wantProgressivePlay = false
                                        playFile = null
                                        selected = null
                                        needPlayRetry = false
                                        isPlayingOk = false
                                        try {
                                            player.stop()
                                            player.clearMediaItems()
                                        } catch (_: Throwable) {
                                        }
                                        statusHint = "正在继续下载「${rec.fileName}」…"
                                        withContext(Dispatchers.IO) {
                                            TorrentEngine.resumeDownload(context, rec)
                                        }
                                        downloading = true
                                        progress = TorrentEngine.snapshot()
                                        val snap = progress
                                        if (snap != null && snap.isFinished) {
                                            statusHint = "已下载完成"
                                        } else {
                                            statusHint =
                                                "已继续下载 · ${formatSpeed(snap?.downloadRate ?: 0)} · 完成后进「我的下载」"
                                        }
                                    } catch (t: Throwable) {
                                        downloading = false
                                        wantProgressivePlay = false
                                        error = t.message ?: "继续下载失败"
                                        statusHint = null
                                    }
                                }
                            }
                        },
                        onDelete = { TorrentDownloadStore.remove(rec.id) }
                    )
                }
            }

            if (files.isNotEmpty()) {
                item(key = "files_title") {
                    Text(
                        "点选视频开始播放 / 下载",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                }
                items(files, key = { "f_${it.index}_${it.path}" }) { file ->
                    FileRow(
                        file = file,
                        selected = selected?.index == file.index,
                        enabled = !loading && torrentBytes != null,
                        onClick = {
                            hideKeyboard()
                            if (file.isVideo || file.size > 0) {
                                startPlay(file)
                            }
                        }
                    )
                }
            }

            item(key = "bottom_pad") {
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                isPlayingOk = false
                needPlayRetry = true
                playFailCount++
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                if (playing) {
                    isPlayingOk = true
                    needPlayRetry = false
                    playFailCount = 0
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    isPlayingOk = true
                    needPlayRetry = false
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
}

@Composable
private fun DownloadRecordRow(
    rec: TorrentDownloadRecord,
    onPlay: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit
) {
    val pct = (rec.progress * 100).toInt().coerceIn(0, 100)
    val canResume = !rec.isFinished && !rec.isActive
    val stateText = when {
        rec.isFinished -> "已完成"
        rec.isActive -> "下载中"
        rec.state == "STOPPED" -> "已停止"
        else -> rec.state
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(BgCard, RoundedCornerShape(10.dp))
            .clickable(onClick = onPlay)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            rec.fileName,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "${rec.torrentName} · $stateText · $pct%",
            color = TextMuted,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp)
        )
        if (!rec.isFinished) {
            LinearProgressIndicator(
                progress = { rec.progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .height(4.dp),
                color = Accent,
                trackColor = Bg
            )
            // 始终展示速度（含 0），下载中额外显示同伴数
            Text(
                buildString {
                    append(formatSize(rec.doneBytes))
                    append(" / ")
                    append(formatSize(rec.totalBytes))
                    append(" · ")
                    append(formatSpeed(rec.downloadRate))
                    if (rec.isActive || rec.numPeers > 0) {
                        append(" · 同伴 ")
                        append(rec.numPeers)
                    }
                },
                color = if (rec.isActive) Accent else TextMuted,
                fontSize = 11.sp,
                fontWeight = if (rec.isActive) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (canResume) {
                Text(
                    "继续下载",
                    color = Color(0xFFB8F080),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(onClick = onResume)
                )
            }
            Text(
                if (rec.isFinished || rec.doneBytes > 0) "点击播放" else "等待数据",
                color = Accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                "删除记录",
                color = Color(0xFFFF8A80),
                fontSize = 11.sp,
                modifier = Modifier.clickable(onClick = onDelete)
            )
        }
    }
}

@Composable
private fun FileRow(
    file: TorrentFileItem,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (selected) Accent.copy(alpha = 0.18f) else BgCard,
                RoundedCornerShape(10.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (file.isVideo) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.padding(end = 8.dp)
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                file.path.substringAfterLast('/'),
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                buildString {
                    append(formatSize(file.size))
                    if (file.isVideo) append(" · 视频 · 点按播放")
                    else append(" · 点按下载")
                },
                color = TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

private fun stateLabel(state: String): String = when (state) {
    "CHECKING_FILES", "CHECKING_RESUME_DATA", "CHECKING" -> "校验中"
    "DOWNLOADING_METADATA" -> "获取元数据"
    "DOWNLOADING" -> "下载中"
    "FINISHED", "SEEDING" -> "已完成"
    "QUEUED_FOR_CHECKING" -> "排队校验"
    "PAUSED", "QUEUED" -> "下载中"
    else -> if (state.isBlank()) "下载中" else state
}

private fun formatSpeed(bytesPerSec: Int): String {
    if (bytesPerSec <= 0) return "0 KB/s"
    val kb = bytesPerSec / 1024.0
    return if (kb < 1024) String.format(Locale.US, "%.0f KB/s", kb)
    else String.format(Locale.US, "%.1f MB/s", kb / 1024.0)
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}
