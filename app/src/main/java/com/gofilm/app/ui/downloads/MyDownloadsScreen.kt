package com.gofilm.app.ui.downloads

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.PlayerView
import com.gofilm.app.data.torrent.TorrentDownloadRecord
import com.gofilm.app.data.torrent.TorrentDownloadStore
import com.gofilm.app.data.torrent.TorrentPaths
import com.gofilm.app.ui.theme.Accent
import com.gofilm.app.ui.theme.Bg
import com.gofilm.app.ui.theme.BgCard
import com.gofilm.app.ui.theme.TextMuted
import com.gofilm.app.ui.theme.TextSecondary
import java.io.File
import java.util.Locale

/**
 * 我的下载：展示已完成并保存在手机本地的资源，支持播放 / 删除。
 */
@OptIn(UnstableApi::class)
@Composable
fun MyDownloadsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val records by TorrentDownloadStore.records.collectAsState()

    LaunchedEffect(Unit) {
        TorrentDownloadStore.init(context)
    }

    val finished = remember(records) {
        records
            .filter { it.isFinished && it.state != "MISSING" }
            .filter { File(it.filePath).exists() }
            .sortedByDescending { it.updatedAt }
    }

    // 扫描目录中的视频文件（兼容已复制到根目录的）
    val dirFiles = remember(finished, records) {
        val dir = TorrentPaths.myDownloadsDir(context)
        val known = finished.map { File(it.filePath).absolutePath }.toSet()
        dir.walkTopDown()
            .maxDepth(3)
            .filter { it.isFile && isVideoFile(it.name) && it.length() > 0L }
            .filter { it.absolutePath !in known }
            .sortedByDescending { it.lastModified() }
            .toList()
    }

    var playFile by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val player = remember {
        ExoPlayer.Builder(context).build().apply { playWhenReady = true }
    }
    val mediaSourceFactory = remember {
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

    fun play(path: String) {
        val f = File(path)
        if (!f.exists()) {
            error = "文件不存在"
            return
        }
        error = null
        playFile = f
        try {
            val item = MediaItem.fromUri(Uri.fromFile(f))
            player.setMediaSource(mediaSourceFactory.createMediaSource(item))
            player.prepare()
            player.playWhenReady = true
            player.play()
        } catch (t: Throwable) {
            error = "无法播放：${t.message}"
            playFile = null
        }
    }

    fun deleteRecord(rec: TorrentDownloadRecord, deleteFile: Boolean) {
        if (deleteFile) {
            try {
                File(rec.filePath).delete()
            } catch (_: Throwable) {
            }
        }
        TorrentDownloadStore.remove(rec.id)
        if (playFile?.absolutePath == rec.filePath) {
            try {
                player.stop()
            } catch (_: Throwable) {
            }
            playFile = null
        }
    }

    fun deleteLooseFile(f: File) {
        try {
            f.delete()
        } catch (_: Throwable) {
        }
        if (playFile?.absolutePath == f.absolutePath) {
            try {
                player.stop()
            } catch (_: Throwable) {
            }
            playFile = null
        }
    }

    BackHandler {
        if (playFile != null) {
            try {
                player.stop()
            } catch (_: Throwable) {
            }
            playFile = null
        } else {
            onBack()
        }
    }

    val savePath = remember {
        TorrentPaths.myDownloadsDir(context).absolutePath
    }

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
                try {
                    player.pause()
                } catch (_: Throwable) {
                }
                onBack()
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                "我的下载",
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
            Text(
                playFile!!.name,
                color = TextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                "种子/磁力下载完成的资源保存在手机本地，可离线播放。",
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
            Row(
                Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.padding(end = 6.dp)
                )
                Text(
                    savePath,
                    color = TextMuted,
                    fontSize = 10.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
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
            Text(
                "共 ${finished.size + dirFiles.size} 个",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 14.dp, bottom = 8.dp)
            )
        }

        if (finished.isEmpty() && dirFiles.isEmpty()) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text("暂无已完成下载", color = TextMuted, fontSize = 14.sp)
            }
        } else {
            LazyColumn(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(finished, key = { it.id }) { rec ->
                    DownloadItem(
                        title = rec.fileName,
                        subtitle = "${rec.torrentName} · ${formatSize(rec.totalBytes)}",
                        onPlay = { play(rec.filePath) },
                        onDelete = { deleteRecord(rec, deleteFile = true) }
                    )
                }
                items(dirFiles, key = { it.absolutePath }) { f ->
                    DownloadItem(
                        title = f.name,
                        subtitle = "本地文件 · ${formatSize(f.length())}",
                        onPlay = { play(f.absolutePath) },
                        onDelete = { deleteLooseFile(f) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadItem(
    title: String,
    subtitle: String,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(BgCard, RoundedCornerShape(12.dp))
            .clickable(onClick = onPlay)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.PlayArrow,
            contentDescription = null,
            tint = Accent,
            modifier = Modifier.padding(end = 10.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                color = TextMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp)
            )
            Text(
                "点击播放",
                color = Accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "删除",
                tint = Color(0xFFFF8A80)
            )
        }
    }
}

private fun isVideoFile(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in setOf(
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "ts", "m4v", "mpeg", "mpg", "3gp"
    )
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}
