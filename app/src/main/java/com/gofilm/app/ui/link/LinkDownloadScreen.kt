package com.gofilm.app.ui.link

import android.Manifest
import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.PlayerView
import com.gofilm.app.data.link.GallerySaver
import com.gofilm.app.data.link.LinkDownloadEngine
import com.gofilm.app.data.link.LinkDownloadRecord
import com.gofilm.app.data.link.LinkDownloadStore
import com.gofilm.app.ui.theme.Accent
import com.gofilm.app.ui.theme.Bg
import com.gofilm.app.ui.theme.BgCard
import com.gofilm.app.ui.theme.TextMuted
import com.gofilm.app.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@OptIn(UnstableApi::class)
@Composable
fun LinkDownloadScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val records by LinkDownloadStore.records.collectAsState()

    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var resolving by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var playFile by remember { mutableStateOf<File?>(null) }

    // 解析结果（未下载）
    var resolved by remember { mutableStateOf<LinkDownloadEngine.ResolveResult?>(null) }
    var resolvedSource by remember { mutableStateOf("") }
    // 刚下完的本地文件
    var lastLocalFile by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(Unit) {
        LinkDownloadStore.init(context)
    }

    val player = remember {
        ExoPlayer.Builder(context).build().apply { playWhenReady = true }
    }
    val mediaFactory = remember {
        ProgressiveMediaSource.Factory(
            DefaultDataSource.Factory(context, FileDataSource.Factory()),
            DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
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

    var pendingGalleryFile by remember { mutableStateOf<File?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val f = pendingGalleryFile
        pendingGalleryFile = null
        if (granted && f != null) {
            scope.launch {
                saveToGallery(context, f) { msg, ok ->
                    if (ok) status = msg else error = msg
                }
            }
        } else if (!granted) {
            error = "需要存储权限才能写入相册（Android 9 及以下）"
        }
    }

    fun hideKb() {
        focus.clearFocus()
        keyboard?.hide()
    }

    fun play(path: String) {
        val f = File(path)
        if (!f.exists()) {
            error = "文件不存在"
            return
        }
        playFile = f
        try {
            player.setMediaSource(mediaFactory.createMediaSource(MediaItem.fromUri(Uri.fromFile(f))))
            player.prepare()
            player.play()
            status = "正在播放 ${f.name}"
        } catch (t: Throwable) {
            error = "无法播放：${t.message}"
        }
    }

    fun requestSaveToGallery(file: File) {
        error = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            scope.launch {
                saveToGallery(context, file) { msg, ok ->
                    if (ok) {
                        status = msg
                        Toast.makeText(context, "已保存到相册", Toast.LENGTH_SHORT).show()
                    } else error = msg
                }
            }
        } else {
            val perm = Manifest.permission.WRITE_EXTERNAL_STORAGE
            val ok = ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
            if (ok) {
                scope.launch {
                    saveToGallery(context, file) { msg, success ->
                        if (success) {
                            status = msg
                            Toast.makeText(context, "已保存到相册", Toast.LENGTH_SHORT).show()
                        } else error = msg
                    }
                }
            } else {
                pendingGalleryFile = file
                permissionLauncher.launch(perm)
            }
        }
    }

    fun doResolve() {
        hideKb()
        val raw = input.trim()
        if (raw.isBlank()) {
            error = "请粘贴视频链接或抖音分享文案"
            return
        }
        val extracted = LinkDownloadEngine.extractUrlFromShareText(raw)
        if (extracted.isBlank()) {
            error = "未找到 https 链接。抖音请「复制链接」整段粘贴"
            return
        }
        if (extracted != raw) {
            input = extracted
        }
        resolving = true
        downloading = false
        error = null
        progress = 0f
        resolved = null
        lastLocalFile = null
        status = "正在解析：$extracted"
        scope.launch {
            val result = LinkDownloadEngine.resolve(raw)
            resolving = false
            result.onSuccess { r ->
                resolved = r
                resolvedSource = raw
                status = buildString {
                    append("解析成功\n")
                    append("标题：${r.title}\n")
                    append("格式：${r.ext}")
                    r.extractor?.let { append(" · $it") }
                    append("\n请点击下方「下载」保存")
                }
            }.onFailure {
                error = it.message ?: "解析失败"
                status = null
            }
        }
    }

    fun doDownload(alsoGallery: Boolean) {
        hideKb()
        val r = resolved
        if (r == null) {
            error = "请先点击「解析」"
            return
        }
        downloading = true
        error = null
        progress = 0f
        status = "正在下载：${r.title}"
        val extracted = LinkDownloadEngine.extractUrlFromShareText(resolvedSource.ifBlank { input })
        scope.launch {
            val rec = LinkDownloadStore.create(
                title = r.title,
                sourceUrl = extracted,
                mediaUrl = r.mediaUrl
            )
            LinkDownloadStore.update(rec.id, state = "DOWNLOADING", mediaUrl = r.mediaUrl, title = r.title)
            val dl = LinkDownloadEngine.download(
                context = context,
                recordId = rec.id,
                mediaUrl = r.mediaUrl,
                title = r.title,
                ext = r.ext,
                sourceUrl = resolvedSource.ifBlank { input }
            ) { done, total, p ->
                progress = if (p > 0f) p else progress
                if (total > 0) {
                    status = "下载中 ${(p * 100).toInt()}% · ${formatSize(done)} / ${formatSize(total)}"
                } else if (done > 0) {
                    status = "下载中 ${formatSize(done)}…"
                }
            }
            downloading = false
            dl.onSuccess { file ->
                lastLocalFile = file
                status = "已下载到本应用：${file.name}（${formatSize(file.length())}）"
                progress = 1f
                play(file.absolutePath)
                if (alsoGallery) {
                    requestSaveToGallery(file)
                }
            }.onFailure {
                error = it.message ?: "下载失败"
            }
        }
    }

    BackHandler {
        if (playFile != null) {
            try {
                player.stop()
            } catch (_: Throwable) {
            }
            playFile = null
        } else onBack()
    }

    val busy = resolving || downloading

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
                hideKb()
                onBack()
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                "链接下载",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.weight(1f)
            )
        }

        if (playFile != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black)
            ) {
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
            }
        }

        LazyColumn(
            Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)
        ) {
            item {
                Text(
                    "① 粘贴链接 → ② 解析 → ③ 下载\n" +
                        "· 支持抖音分享文案（自动提取短链）\n" +
                        "· 下载到应用内，可再保存到系统相册\n" +
                        "· 视频号暂不支持",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
            item {
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        // 改文案后清空旧解析结果，避免下错
                        if (resolved != null) {
                            resolved = null
                            lastLocalFile = null
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text("粘贴抖音分享全文或视频链接", color = TextMuted, fontSize = 13.sp)
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
                    keyboardActions = KeyboardActions(onDone = { doResolve() })
                )
            }
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            val t = clipboard.getText()?.text.orEmpty().trim()
                            if (t.isNotBlank()) {
                                input = t
                                resolved = null
                                lastLocalFile = null
                                status = "已粘贴，请点「解析」"
                                error = null
                            } else error = "剪贴板为空"
                            hideKb()
                        },
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BgCard,
                            contentColor = Accent
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.ContentPaste, null)
                        Spacer(Modifier.width(6.dp))
                        Text("粘贴", fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick = { doResolve() },
                        enabled = !busy && input.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BgCard,
                            contentColor = Accent
                        ),
                        modifier = Modifier
                            .weight(1.2f)
                            .height(46.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Search, null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (resolving) "解析中…" else "解析", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // 解析结果卡片
            resolved?.let { r ->
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(BgCard, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Text("解析结果", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Accent)
                        Text(
                            r.title,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        Text(
                            "格式 .${r.ext}" + (r.extractor?.let { " · $it" } ?: ""),
                            color = TextMuted,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Text(
                            "媒体地址已就绪（已隐藏完整 URL）",
                            color = TextMuted,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { doDownload(alsoGallery = false) },
                        enabled = !busy && resolved != null,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Accent,
                            contentColor = Color(0xFF1A1000)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Download, null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (downloading) "下载中…" else "下载到应用", fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick = { doDownload(alsoGallery = true) },
                        enabled = !busy && resolved != null,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2A3A20),
                            contentColor = Color(0xFFB8F080)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.PhotoLibrary, null)
                        Spacer(Modifier.width(6.dp))
                        Text("下载到相册", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // 已下载：可再存相册
            lastLocalFile?.takeIf { it.exists() }?.let { f ->
                item {
                    Button(
                        onClick = { requestSaveToGallery(f) },
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BgCard,
                            contentColor = Accent
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.PhotoLibrary, null)
                        Spacer(Modifier.width(8.dp))
                        Text("将已下载文件保存到相册", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            if (resolving || downloading || progress > 0f) {
                item {
                    LinearProgressIndicator(
                        progress = {
                            when {
                                resolving -> 0f
                                progress > 0f -> progress
                                else -> 0f
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        color = Accent,
                        trackColor = BgCard
                    )
                    Text(
                        when {
                            resolving -> "解析中…"
                            downloading -> "下载进度 ${(progress * 100).toInt()}%"
                            else -> ""
                        },
                        color = TextMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            status?.let {
                item { Text(it, color = Accent, fontSize = 12.sp) }
            }
            error?.let {
                item { Text(it, color = Color(0xFFFF8A80), fontSize = 12.sp) }
            }

            if (records.isNotEmpty()) {
                item {
                    Text(
                        "下载记录",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                items(records, key = { it.id }) { rec ->
                    LinkRecordRow(
                        rec = rec,
                        onPlay = {
                            if (rec.filePath.isNotBlank()) play(rec.filePath)
                            else error = "文件未就绪，请先下载"
                        },
                        onSaveGallery = {
                            val f = File(rec.filePath)
                            if (f.exists()) requestSaveToGallery(f)
                            else error = "文件不存在"
                        },
                        onDelete = { LinkDownloadStore.remove(rec.id, deleteFile = true) }
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private suspend fun saveToGallery(
    context: android.content.Context,
    file: File,
    done: (String, Boolean) -> Unit
) {
    val r = withContext(Dispatchers.IO) {
        GallerySaver.saveVideoToGallery(context, file, file.name)
    }
    r.onSuccess {
        done("已保存到系统相册 Movies/Alucard（或 Pictures/Alucard）", true)
    }.onFailure {
        done(it.message ?: "保存相册失败", false)
    }
}

@Composable
private fun LinkRecordRow(
    rec: LinkDownloadRecord,
    onPlay: () -> Unit,
    onSaveGallery: () -> Unit,
    onDelete: () -> Unit
) {
    val stateText = when {
        rec.isFinished -> "已完成"
        rec.state == "DOWNLOADING" -> "下载中 ${(rec.progress * 100).toInt()}%"
        rec.state == "ERROR" -> "失败"
        else -> rec.state
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(BgCard, RoundedCornerShape(12.dp))
            .clickable(onClick = onPlay)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.PlayArrow, null, tint = Accent, modifier = Modifier.padding(end = 8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    rec.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "$stateText · ${formatSize(rec.doneBytes)}/${formatSize(rec.totalBytes)}",
                    color = TextMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        if (!rec.isFinished && rec.progress > 0f && rec.progress < 1f) {
            LinearProgressIndicator(
                progress = { rec.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .height(4.dp),
                color = Accent,
                trackColor = Bg
            )
        }
        rec.error?.let {
            Text(it, color = Color(0xFFFF8A80), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "播放",
                color = Accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable(onClick = onPlay)
            )
            if (rec.isFinished) {
                Text(
                    "存相册",
                    color = Color(0xFFB8F080),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable(onClick = onSaveGallery)
                )
            }
            Text(
                "删除",
                color = Color(0xFFFF8A80),
                fontSize = 11.sp,
                modifier = Modifier.clickable(onClick = onDelete)
            )
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}
