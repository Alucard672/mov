package com.gofilm.app.ui.play

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.gofilm.app.GoFilmApp
import com.gofilm.app.data.dto.PlayInfoData
import com.gofilm.app.data.dto.PlaySource
import com.gofilm.app.ui.components.ErrorBox
import com.gofilm.app.ui.components.LoadingBox
import com.gofilm.app.ui.theme.Accent
import com.gofilm.app.ui.theme.AccentSoft
import com.gofilm.app.ui.theme.Bg
import com.gofilm.app.ui.theme.BgCard
import com.gofilm.app.ui.theme.TextMuted
import com.gofilm.app.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** 走服务器反代，避免手机直连 CDN 被 403 防盗链。相对 /proxy 也会补全 origin。 */
private fun toServerProxyUrl(mediaUrl: String, apiBaseUrl: String): String =
    com.gofilm.app.data.repo.PlayUrlProber.toProxyUrl(mediaUrl, apiBaseUrl)

private fun isHlsUrl(url: String): Boolean {
    val u = url.lowercase()
    return u.contains(".m3u8") || u.contains("/proxy")
}

private const val BROWSER_UA =
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayScreen(
    filmId: Long,
    playFrom: String,
    episode: Int,
    resumePositionMs: Long = 0L,
    onBack: () -> Unit
) {
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var data by remember { mutableStateOf<PlayInfoData?>(null) }
    var currentFrom by remember { mutableStateOf(playFrom) }
    var currentEp by remember { mutableIntStateOf(episode) }
    var playUrl by remember { mutableStateOf<String?>(null) }
    var seekOnce by remember { mutableStateOf(resumePositionMs > 0L) }
    var fullscreen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = context as? Activity
    val view = LocalView.current

    fun load(from: String, ep: Int) {
        currentFrom = from
        currentEp = ep
        seekOnce = false
        loading = true
        error = null
    }

    fun setFullscreen(enabled: Boolean) {
        fullscreen = enabled
        val act = activity ?: return
        val window = act.window
        val controller = WindowInsetsControllerCompat(window, view)
        if (enabled) {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            WindowCompat.setDecorFitsSystemWindows(window, false)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            WindowCompat.setDecorFitsSystemWindows(window, true)
            controller.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    BackHandler {
        if (fullscreen) {
            setFullscreen(false)
        } else {
            onBack()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // 离开播放页恢复竖屏与系统栏
            activity?.let { act ->
                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                val window = act.window
                WindowCompat.setDecorFitsSystemWindows(window, true)
                WindowInsetsControllerCompat(window, view)
                    .show(WindowInsetsCompat.Type.systemBars())
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    LaunchedEffect(filmId, currentFrom, currentEp) {
        loading = true
        error = null
        val apiBase = runCatching {
            GoFilmApp.instance.filmRepository.currentBaseUrl()
        }.getOrElse {
            GoFilmApp.instance.filmRepository.baseUrlFlow.first()
        }
        GoFilmApp.instance.filmRepository.filmPlayInfo(filmId, currentFrom, currentEp)
            .onSuccess {
                data = it
                val raw = it.current?.link.orEmpty()
                playUrl = if (raw.isBlank()) null else toServerProxyUrl(raw, apiBase)
                if (currentFrom.isBlank()) {
                    currentFrom = it.currentPlayFrom
                }
                if (raw.isBlank()) {
                    error = "当前集没有可用播放地址，请换集或换线路"
                }
                loading = false
            }
            .onFailure {
                error = it.message ?: "播放信息加载失败"
                loading = false
            }
    }

    val mediaDataSourceFactory = remember {
        val okHttp = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val req = chain.request()
                val url = req.url
                val builder = req.newBuilder()
                    .header("User-Agent", BROWSER_UA)
                    .header("Accept", "*/*")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                val host = url.host
                if (!host.contains("120.27.148.45") &&
                    !host.contains("alucard.top", ignoreCase = true) &&
                    !host.equals("localhost", true)
                ) {
                    builder.header("Referer", "${url.scheme}://${url.host}/")
                    builder.header("Origin", "${url.scheme}://${url.host}")
                }
                chain.proceed(builder.build())
            }
            .build()
        OkHttpDataSource.Factory(okHttp).setUserAgent(BROWSER_UA)
    }

    val player = remember {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context).setDataSourceFactory(mediaDataSourceFactory)
            )
            .build()
            .apply { playWhenReady = true }
    }

    fun saveProgress(force: Boolean = false) {
        val detail = data?.detail ?: return
        val pos = player.currentPosition
        val dur = player.duration.coerceAtLeast(0L)
        if (!force && pos < 3_000) return
        val label = data?.current?.episode
            ?: detail.list.orEmpty().firstOrNull { it.id == currentFrom }
                ?.linkList?.getOrNull(currentEp)?.episode
            ?: "第${currentEp + 1}集"
        scope.launch {
            GoFilmApp.instance.localRepository.saveProgress(
                filmId = filmId,
                name = detail.name,
                picture = detail.picture,
                playFrom = currentFrom,
                episode = currentEp,
                episodeLabel = label,
                positionMs = pos,
                durationMs = if (dur > 0) dur else 0L
            )
        }
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && seekOnce && resumePositionMs > 3_000) {
                    player.seekTo(resumePositionMs)
                    seekOnce = false
                }
            }

            override fun onPlayerError(err: PlaybackException) {
                val msg = (err.cause?.message ?: err.message).orEmpty()
                error = when {
                    msg.contains("None of the available extractors", ignoreCase = true) ||
                        msg.contains("could not read the stream", ignoreCase = true) ->
                        "无法识别视频流（可能不是有效 m3u8）。请换线路，或更新到最新版 App"
                    msg.contains("403") ->
                        "片源返回 403（防盗链）。请换线路或重进播放页"
                    msg.contains("404") ->
                        "播放地址失效（404），请换线路或换一集"
                    msg.contains("401") ->
                        "片源拒绝访问（401），请换线路"
                    msg.isBlank() ->
                        "播放失败（线路可能失效，请换源）"
                    else -> msg.take(200)
                }
            }
        }
        player.addListener(listener)
        onDispose {
            saveProgress(force = true)
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(player, playUrl) {
        while (isActive) {
            delay(8_000)
            if (player.isPlaying) saveProgress()
        }
    }

    LaunchedEffect(playUrl) {
        val url = playUrl
        if (!url.isNullOrBlank()) {
            // 代理 URL 常无 .m3u8 后缀，必须强制 HLS，否则会走 Progressive 解析失败
            if (isHlsUrl(url)) {
                val item = MediaItem.Builder()
                    .setUri(url)
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .build()
                val source = HlsMediaSource.Factory(mediaDataSourceFactory)
                    .createMediaSource(item)
                player.setMediaSource(source)
            } else {
                player.setMediaItem(MediaItem.fromUri(url))
            }
            player.prepare()
            player.play()
        }
    }

    val detail = data?.detail
    val sources: List<PlaySource> = detail?.list.orEmpty()
        .map { it.copy(linkList = it.linkList.filter { l -> l.link.isNotBlank() }) }
        .filter { it.linkList.isNotEmpty() }
    val activeSource = sources.firstOrNull { it.id == currentFrom } ?: sources.firstOrNull()
    val episodes = activeSource?.linkList.orEmpty()

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .then(if (!fullscreen) Modifier.navigationBarsPadding() else Modifier)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .then(
                    if (fullscreen) Modifier.weight(1f)
                    else Modifier.aspectRatio(16f / 9f)
                )
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
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                    }
                },
                update = {
                    it.player = player
                    it.resizeMode = if (fullscreen) {
                        AspectRatioFrameLayout.RESIZE_MODE_FIT
                    } else {
                        AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .then(if (fullscreen) Modifier.statusBarsPadding() else Modifier)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        if (fullscreen) {
                            setFullscreen(false)
                        } else {
                            saveProgress(force = true)
                            onBack()
                        }
                    }
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.White
                    )
                }
                IconButton(onClick = { setFullscreen(!fullscreen) }) {
                    Icon(
                        if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                        contentDescription = if (fullscreen) "退出全屏" else "全屏",
                        tint = Color.White
                    )
                }
            }

            if (loading) {
                LoadingBox(Modifier.fillMaxSize())
            }
        }

        if (!fullscreen) {
            when {
                error != null && data == null -> ErrorBox(
                    error!!,
                    onRetry = { load(currentFrom, currentEp) }
                )
                else -> {
                    Column(
                        Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        Text(
                            detail?.name.orEmpty(),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "${activeSource?.name.orEmpty().ifBlank { "线路" }} · ${data?.current?.episode ?: "第 ${currentEp + 1} 集"} · 共 ${episodes.size} 集",
                            color = TextMuted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                        )
                        if (!error.isNullOrBlank()) {
                            Text(
                                error!!,
                                color = Color(0xFFFF5C7A),
                                fontSize = 12.sp,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }

                        if (sources.isNotEmpty()) {
                            Text("播放源", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Spacer(Modifier.height(8.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                sources.forEach { source ->
                                    val active = source.id == (activeSource?.id)
                                    Text(
                                        "${source.name.ifBlank { source.id }} (${source.linkList.size})",
                                        color = if (active) Accent else TextSecondary,
                                        fontSize = 12.sp,
                                        modifier = Modifier
                                            .background(
                                                if (active) AccentSoft else BgCard,
                                                RoundedCornerShape(10.dp)
                                            )
                                            .clickable {
                                                saveProgress(force = true)
                                                load(source.id, 0)
                                            }
                                            .padding(horizontal = 12.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }

                        if (episodes.isNotEmpty()) {
                            Spacer(Modifier.height(14.dp))
                            Text(
                                "选集 · ${episodes.size}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Spacer(Modifier.height(8.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                episodes.forEachIndexed { idx, ep ->
                                    val active = idx == currentEp
                                    Text(
                                        ep.episode.ifBlank { "${idx + 1}" },
                                        color = if (active) Accent else TextSecondary,
                                        fontSize = 12.sp,
                                        modifier = Modifier
                                            .background(
                                                if (active) AccentSoft else BgCard,
                                                RoundedCornerShape(10.dp)
                                            )
                                            .clickable {
                                                saveProgress(force = true)
                                                load(activeSource?.id.orEmpty(), idx)
                                            }
                                            .padding(horizontal = 12.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    if (currentEp > 0) {
                                        saveProgress(force = true)
                                        load(activeSource?.id.orEmpty(), currentEp - 1)
                                    }
                                },
                                enabled = currentEp > 0,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = BgCard,
                                    contentColor = Color.White
                                ),
                                modifier = Modifier.weight(1f)
                            ) { Text("上一集") }
                            Button(
                                onClick = {
                                    if (currentEp < episodes.lastIndex) {
                                        saveProgress(force = true)
                                        load(activeSource?.id.orEmpty(), currentEp + 1)
                                    }
                                },
                                enabled = currentEp < episodes.lastIndex,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Accent,
                                    contentColor = Color(0xFF1A1000)
                                ),
                                modifier = Modifier.weight(1f)
                            ) { Text("下一集") }
                        }
                    }
                }
            }
        }
    }
}
