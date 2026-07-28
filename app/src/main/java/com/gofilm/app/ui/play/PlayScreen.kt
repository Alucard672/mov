package com.gofilm.app.ui.play

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
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
import com.gofilm.app.data.local.SettingsStore
import com.gofilm.app.ui.components.ErrorBox
import com.gofilm.app.ui.components.LoadingBox
import com.gofilm.app.ui.theme.Accent
import com.gofilm.app.ui.theme.AccentSoft
import com.gofilm.app.ui.theme.Bg
import com.gofilm.app.ui.theme.BgCard
import com.gofilm.app.ui.theme.TextMuted
import com.gofilm.app.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.math.abs

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

private val SKIP_SEC_OPTIONS = listOf(0, 30, 60, 90, 120, 180, 300)
private val SPEED_OPTIONS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

private fun formatSkipLabel(sec: Int): String = when {
    sec <= 0 -> "关"
    sec < 60 -> "${sec}s"
    sec % 60 == 0 -> "${sec / 60}分"
    else -> "${sec / 60}分${sec % 60}s"
}

private fun formatSpeedLabel(s: Float): String =
    if (s == s.toLong().toFloat()) "${s.toLong()}x" else "${s}x"

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayScreen(
    filmId: Long,
    playFrom: String,
    episode: Int,
    resumePositionMs: Long = 0L,
    onBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER")
    onOpenVideoSettings: () -> Unit = {}
) {
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var data by remember { mutableStateOf<PlayInfoData?>(null) }
    var currentFrom by remember { mutableStateOf(playFrom) }
    var currentEp by remember { mutableIntStateOf(episode) }
    var playUrl by remember { mutableStateOf<String?>(null) }
    var seekOnce by remember { mutableStateOf(resumePositionMs > 0L) }
    // 手动点全屏：锁定横屏；重力横屏时 isLandscape=true 同样走沉浸布局
    var fullscreen by remember { mutableStateOf(false) }
    var gestureHint by remember { mutableStateOf<String?>(null) }
    var showPlaySettings by remember { mutableStateOf(false) }
    var appliedHeadSkipForUrl by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = context as? Activity
    val view = LocalView.current
    val settings = GoFilmApp.instance.settingsStore
    val configuration = LocalConfiguration.current
    val isLandscape =
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    // 重力横屏 或 手动全屏 → 整屏播放（不依赖是否点过全屏按钮）
    val immersivePlayer = fullscreen || isLandscape

    val skipHeadSec by settings.skipHeadSecFlow.collectAsState(SettingsStore.DEFAULT_SKIP_SEC)
    val skipTailSec by settings.skipTailSecFlow.collectAsState(SettingsStore.DEFAULT_SKIP_SEC)
    val savedSpeed by settings.playbackSpeedFlow.collectAsState(1f)
    var playbackSpeed by remember { mutableFloatStateOf(1f) }
    var speedInited by remember { mutableStateOf(false) }

    LaunchedEffect(savedSpeed) {
        if (!speedInited) {
            playbackSpeed = savedSpeed
            speedInited = true
        }
    }

    fun load(from: String, ep: Int) {
        currentFrom = from
        currentEp = ep
        seekOnce = false
        appliedHeadSkipForUrl = null
        loading = true
        error = null
    }

    fun applySystemUi(immersive: Boolean) {
        val act = activity ?: return
        val window = act.window
        val controller = WindowInsetsControllerCompat(window, view)
        if (immersive) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            controller.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    fun setFullscreen(enabled: Boolean) {
        fullscreen = enabled
        if (!enabled) showPlaySettings = false
        val act = activity ?: return
        if (enabled) {
            // 手动全屏：锁定横屏，保证立刻进入全屏布局
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            applySystemUi(true)
        } else {
            // 退出：先锁竖屏，避免手机仍横着时继续判定 isLandscape
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            applySystemUi(false)
            scope.launch {
                delay(700)
                if (!fullscreen) {
                    act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
        }
    }

    // 重力横屏 / 手动全屏：系统栏沉浸 + 横屏时强制重算布局
    SideEffect {
        applySystemUi(immersivePlayer)
    }

    // 重力进入横屏：关闭可能挡住点击的设置面板，并确保沉浸式生效
    LaunchedEffect(isLandscape) {
        if (isLandscape) {
            showPlaySettings = false
            applySystemUi(true)
        } else if (!fullscreen) {
            applySystemUi(false)
        }
    }

    BackHandler {
        when {
            showPlaySettings -> showPlaySettings = false
            immersivePlayer -> setFullscreen(false)
            else -> onBack()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            activity?.let { act ->
                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                val window = act.window
                WindowCompat.setDecorFitsSystemWindows(window, true)
                WindowInsetsControllerCompat(window, view)
                    .show(WindowInsetsCompat.Type.systemBars())
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                // 恢复窗口亮度跟随系统
                val lp = window.attributes
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = lp
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

    LaunchedEffect(playbackSpeed) {
        player.setPlaybackSpeed(playbackSpeed)
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

    fun goNextEpisode() {
        val sourcesLocal: List<PlaySource> = data?.detail?.list.orEmpty()
            .map { it.copy(linkList = it.linkList.filter { l -> l.link.isNotBlank() }) }
            .filter { it.linkList.isNotEmpty() }
        val active = sourcesLocal.firstOrNull { it.id == currentFrom } ?: sourcesLocal.firstOrNull()
        val eps = active?.linkList.orEmpty()
        if (currentEp < eps.lastIndex) {
            saveProgress(force = true)
            load(active?.id.orEmpty(), currentEp + 1)
        } else {
            player.pause()
        }
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    val url = playUrl
                    // 续播优先
                    if (seekOnce && resumePositionMs > 3_000) {
                        player.seekTo(resumePositionMs)
                        seekOnce = false
                        appliedHeadSkipForUrl = url
                    } else if (
                        url != null &&
                        appliedHeadSkipForUrl != url &&
                        skipHeadSec > 0 &&
                        resumePositionMs <= 3_000
                    ) {
                        val headMs = skipHeadSec * 1000L
                        val dur = player.duration
                        if (dur <= 0L || headMs < dur / 2) {
                            player.seekTo(headMs)
                        }
                        appliedHeadSkipForUrl = url
                    }
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

    // 片尾自动跳过
    LaunchedEffect(player, playUrl, skipTailSec, currentEp) {
        while (isActive) {
            delay(800)
            if (skipTailSec <= 0) continue
            if (!player.isPlaying) continue
            val dur = player.duration
            val pos = player.currentPosition
            if (dur > 0 && pos > 0 && pos >= dur - skipTailSec * 1000L) {
                // 片太短不跳
                if (dur > skipTailSec * 1000L * 2) {
                    goNextEpisode()
                    delay(1500)
                }
            }
        }
    }

    LaunchedEffect(player, playUrl) {
        while (isActive) {
            delay(8_000)
            if (player.isPlaying) saveProgress()
        }
    }

    // 手势提示自动消失
    LaunchedEffect(gestureHint) {
        if (gestureHint != null) {
            delay(800)
            gestureHint = null
        }
    }

    LaunchedEffect(playUrl) {
        val url = playUrl
        if (!url.isNullOrBlank()) {
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

    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    // 手势桥接类型固定，便于 PlayerVideoSurface 使用
    // （上面 remember 的匿名对象属性与此一致）

    // 外层 Box：设置面板盖住整页（含竖屏下方列表区），避免卡在 16:9 播放器内显示不全
    Box(
        Modifier
            .fillMaxSize()
            .background(if (immersivePlayer) Color.Black else Bg)
    ) {
    // 播放器容器始终挂在同一位置，避免重力翻转时 AndroidView 销毁重建导致无法全屏/点不到控件
    Column(
        Modifier
            .fillMaxSize()
            .then(if (immersivePlayer) Modifier else Modifier.navigationBarsPadding())
    ) {
        Box(
            Modifier
                .background(Color.Black)
                .then(
                    if (immersivePlayer) {
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                    }
                )
        ) {
            PlayerVideoSurface(
                player = player,
                onHint = { gestureHint = it },
                activity = activity,
                audioManager = audioManager,
                showPlaySettings = showPlaySettings,
                immersive = immersivePlayer,
                modifier = Modifier.fillMaxSize()
            )

            // 顶栏：返回 / 设置 / 全屏 —— 统一放顶部，避开侧边手势与底部进度条
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .zIndex(60f)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        if (immersivePlayer) {
                            setFullscreen(false)
                        } else {
                            saveProgress(force = true)
                            onBack()
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(24.dp))
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.White
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(
                        onClick = { showPlaySettings = true },
                        modifier = Modifier
                            .size(48.dp)
                            .background(Color(0xCC000000), RoundedCornerShape(24.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(24.dp))
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "播放设置",
                            tint = Color.White
                        )
                    }
                    IconButton(
                        onClick = { setFullscreen(!immersivePlayer) },
                        modifier = Modifier
                            .size(48.dp)
                            .background(Color(0xCC000000), RoundedCornerShape(24.dp))
                    ) {
                        Icon(
                            if (immersivePlayer) {
                                Icons.Default.FullscreenExit
                            } else {
                                Icons.Default.Fullscreen
                            },
                            contentDescription = if (immersivePlayer) "退出全屏" else "全屏",
                            tint = Color.White
                        )
                    }
                }
            }

            gestureHint?.let { hint ->
                Text(
                    hint,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .zIndex(65f)
                        .background(Color(0xCC000000), RoundedCornerShape(12.dp))
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                )
            }

            if (loading) {
                LoadingBox(Modifier.fillMaxSize().zIndex(10f))
            }
        }

        // 竖屏才显示选集列表；横屏/全屏只保留播放器
        if (!immersivePlayer) {
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

        // 全屏遮罩设置：竖屏也能完整展示片头/片尾/倍速，不挤在 16:9 播放窗内
        if (showPlaySettings) {
            Box(
                Modifier
                    .fillMaxSize()
                    .zIndex(80f)
                    .background(Color(0x99000000))
                    .clickable { showPlaySettings = false }
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                CompactPlaySettingsPanel(
                    skipHeadSec = skipHeadSec,
                    skipTailSec = skipTailSec,
                    playbackSpeed = playbackSpeed,
                    onSelectHead = { sec ->
                        scope.launch { settings.setSkipHeadSec(sec) }
                    },
                    onSelectTail = { sec ->
                        scope.launch { settings.setSkipTailSec(sec) }
                    },
                    onSelectSpeed = { sp ->
                        playbackSpeed = sp
                        scope.launch { settings.setPlaybackSpeed(sp) }
                    },
                    onClose = { showPlaySettings = false }
                )
            }
        }
    }
}

/** 播放页内嵌紧凑设置：片头 / 片尾 / 倍速 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompactPlaySettingsPanel(
    skipHeadSec: Int,
    skipTailSec: Int,
    playbackSpeed: Float,
    onSelectHead: (Int) -> Unit,
    onSelectTail: (Int) -> Unit,
    onSelectSpeed: (Float) -> Unit,
    onClose: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .widthIn(max = 360.dp)
            .heightIn(max = 520.dp)
            .background(Color(0xF012121A), RoundedCornerShape(14.dp))
            .border(1.dp, Color.White.copy(0.18f), RoundedCornerShape(14.dp))
            // 吃掉点击，避免穿透到遮罩导致面板关闭
            .clickable(enabled = false, onClick = {})
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("播放设置", color = Accent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(
                "关闭",
                color = TextMuted,
                fontSize = 12.sp,
                modifier = Modifier
                    .clickable(onClick = onClose)
                    .padding(4.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text("片头", color = TextSecondary, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        MiniChipRow(
            labels = SKIP_SEC_OPTIONS.map { it to formatSkipLabel(it) },
            active = { it == skipHeadSec },
            onClick = onSelectHead
        )
        Spacer(Modifier.height(8.dp))
        Text("片尾", color = TextSecondary, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        MiniChipRow(
            labels = SKIP_SEC_OPTIONS.map { it to formatSkipLabel(it) },
            active = { it == skipTailSec },
            onClick = onSelectTail
        )
        Spacer(Modifier.height(8.dp))
        Text("倍速", color = TextSecondary, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        MiniChipRow(
            labels = SPEED_OPTIONS.map { it to formatSpeedLabel(it) },
            active = { abs(it - playbackSpeed) < 0.01f },
            onClick = onSelectSpeed
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> MiniChipRow(
    labels: List<Pair<T, String>>,
    active: (T) -> Boolean,
    onClick: (T) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        labels.forEach { (value, label) ->
            val on = active(value)
            Text(
                label,
                color = if (on) Accent else Color.White.copy(0.85f),
                fontSize = 11.sp,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .background(
                        if (on) AccentSoft else Color.White.copy(0.08f),
                        RoundedCornerShape(8.dp)
                    )
                    .clickable { onClick(value) }
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            )
        }
    }
}

/** 播放器画面 + 左右侧亮度/音量手势 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun PlayerVideoSurface(
    player: ExoPlayer,
    onHint: (String?) -> Unit,
    activity: Activity?,
    audioManager: AudioManager,
    showPlaySettings: Boolean,
    immersive: Boolean,
    modifier: Modifier = Modifier
) {
    // 用可变 holder，避免 AndroidView factory 捕获过期回调
    val holder = remember {
        object {
            var onHint: (String?) -> Unit = {}
            var activityRef: Activity? = null
            var audioRef: AudioManager? = null
        }
    }
    holder.onHint = onHint
    holder.activityRef = activity
    holder.audioRef = audioManager

    AndroidView(
        factory = { ctx ->
            val playerView = PlayerView(ctx).apply {
                this.player = player
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                useController = true
                controllerAutoShow = true
                controllerShowTimeoutMs = 3000
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShowNextButton(false)
                setShowPreviousButton(false)
                post {
                    findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.visibility = View.GONE
                    findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.isClickable = false
                }
            }

            fun sideGestureView(isBrightness: Boolean): View {
                return object : View(ctx) {
                    private var startY = 0f
                    private var baseBrightness = 0.5f
                    private var baseVolume = 0
                    private var maxVolume = 1
                    private var dragging = false

                    private fun currentWindowBrightness(): Float {
                        val act = holder.activityRef
                        val winBright = act?.window?.attributes?.screenBrightness ?: -1f
                        if (winBright in 0f..1f) return winBright
                        return try {
                            Settings.System.getInt(
                                ctx.contentResolver,
                                Settings.System.SCREEN_BRIGHTNESS
                            ) / 255f
                        } catch (_: Exception) {
                            0.5f
                        }
                    }

                    override fun onTouchEvent(event: MotionEvent): Boolean {
                        val h = height.coerceAtLeast(1).toFloat()
                        val am = holder.audioRef
                        val act = holder.activityRef
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                startY = event.y
                                dragging = false
                                if (isBrightness) {
                                    baseBrightness = currentWindowBrightness()
                                } else if (am != null) {
                                    maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                        .coerceAtLeast(1)
                                    baseVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                                }
                                parent?.requestDisallowInterceptTouchEvent(true)
                                return true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                val delta = (startY - event.y) / h
                                if (!dragging && abs(event.y - startY) < 8f) return true
                                dragging = true
                                if (isBrightness && act != null) {
                                    val next = (baseBrightness + delta * 1.15f).coerceIn(0.01f, 1f)
                                    val lp = act.window.attributes
                                    lp.screenBrightness = next
                                    act.window.attributes = lp
                                    holder.onHint("亮度 ${(next * 100).toInt()}%")
                                } else if (!isBrightness && am != null) {
                                    val nextVol = (baseVolume + delta * maxVolume * 1.25f)
                                        .toInt().coerceIn(0, maxVolume)
                                    am.setStreamVolume(AudioManager.STREAM_MUSIC, nextVol, 0)
                                    holder.onHint("音量 ${nextVol * 100 / maxVolume}%")
                                }
                                return true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                parent?.requestDisallowInterceptTouchEvent(false)
                                if (!dragging) playerView.performClick()
                                dragging = false
                                return true
                            }
                        }
                        return true
                    }
                }.apply {
                    isClickable = true
                    isFocusable = false
                }
            }

            object : FrameLayout(ctx) {
                val leftZone = sideGestureView(true)
                val rightZone = sideGestureView(false)

                init {
                    addView(
                        playerView,
                        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                    )
                    addView(leftZone)
                    addView(rightZone)
                }

                override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                    super.onLayout(changed, l, t, r, b)
                    val w = r - l
                    val h = b - t
                    val dens = resources.displayMetrics.density
                    // 侧边手势变窄，并加大顶/底留白，避免挡住返回/设置/全屏与进度条
                    val side = (w * 0.20f).toInt().coerceAtLeast(1)
                    val topPad = (88 * dens).toInt()
                    val bottomPad = (120 * dens).toInt()
                    val topY = topPad.coerceAtMost(h / 4)
                    val botY = (h - bottomPad).coerceAtLeast(h * 3 / 5)
                    leftZone.layout(0, topY, side, botY)
                    rightZone.layout(w - side, topY, w, botY)
                    // 手势层在视频之上，但顶栏 Compose 按钮更高层
                    leftZone.bringToFront()
                    rightZone.bringToFront()
                }
            }.also { it.tag = playerView }
        },
        update = { root ->
            val pv = root.tag as? PlayerView
            pv?.player = player
            // 横屏/全屏铺满；竖屏小窗 fit
            pv?.resizeMode = if (immersive) {
                AspectRatioFrameLayout.RESIZE_MODE_FIT
            } else {
                AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            if (showPlaySettings) {
                pv?.hideController()
            }
            // 横竖切换后强制重新 layout 侧边手势区
            root.requestLayout()
        },
        modifier = modifier
    )
}

