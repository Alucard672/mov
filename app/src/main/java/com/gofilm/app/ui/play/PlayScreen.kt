package com.gofilm.app.ui.play

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.widthIn
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
    var fullscreen by remember { mutableStateOf(false) }
    var gestureHint by remember { mutableStateOf<String?>(null) }
    var showPlaySettings by remember { mutableStateOf(false) }
    var appliedHeadSkipForUrl by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = context as? Activity
    val view = LocalView.current
    val settings = GoFilmApp.instance.settingsStore

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

    // 手势回调：update 时刷新，避免 factory 捕获过期状态
    val gestureBridge = remember {
        object {
            var onHint: (String?) -> Unit = {}
            var activityRef: Activity? = null
            var audioRef: AudioManager? = null
        }
    }
    gestureBridge.onHint = { gestureHint = it }
    gestureBridge.activityRef = activity
    gestureBridge.audioRef = audioManager

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
                    val playerView = PlayerView(ctx).apply {
                        this.player = player
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        useController = true
                        controllerAutoShow = true
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                        // 隐藏系统播放器「速度/音频」齿轮，避免与「视频设置」混淆
                        post {
                            findViewById<View>(androidx.media3.ui.R.id.exo_settings)
                                ?.visibility = View.GONE
                            findViewById<View>(androidx.media3.ui.R.id.exo_settings)
                                ?.isClickable = false
                        }
                    }

                    /**
                     * 侧边手势层：叠在 PlayerView 之上，仅左右约 1/3 区域。
                     * 横屏全屏时 PlayerView 会吃掉父布局 touch，必须用独立子 View。
                     */
                    fun sideGestureView(isBrightness: Boolean): View {
                        return object : View(ctx) {
                            private var startY = 0f
                            private var baseBrightness = 0.5f
                            private var baseVolume = 0
                            private var maxVolume = 1
                            private var dragging = false

                            private fun currentWindowBrightness(): Float {
                                val act = gestureBridge.activityRef
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
                                val am = gestureBridge.audioRef
                                val act = gestureBridge.activityRef
                                when (event.actionMasked) {
                                    MotionEvent.ACTION_DOWN -> {
                                        startY = event.y
                                        dragging = false
                                        if (isBrightness) {
                                            baseBrightness = currentWindowBrightness()
                                        } else if (am != null) {
                                            maxVolume = am
                                                .getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                                .coerceAtLeast(1)
                                            baseVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                                        }
                                        parent?.requestDisallowInterceptTouchEvent(true)
                                        return true
                                    }
                                    MotionEvent.ACTION_MOVE -> {
                                        val delta = (startY - event.y) / h
                                        if (!dragging && abs(event.y - startY) < 8f) {
                                            return true
                                        }
                                        dragging = true
                                        if (isBrightness && act != null) {
                                            val next =
                                                (baseBrightness + delta * 1.15f).coerceIn(0.01f, 1f)
                                            val lp = act.window.attributes
                                            lp.screenBrightness = next
                                            act.window.attributes = lp
                                            gestureBridge.onHint("亮度 ${(next * 100).toInt()}%")
                                        } else if (!isBrightness && am != null) {
                                            val nextVol =
                                                (baseVolume + delta * maxVolume * 1.25f)
                                                    .toInt()
                                                    .coerceIn(0, maxVolume)
                                            am.setStreamVolume(
                                                AudioManager.STREAM_MUSIC,
                                                nextVol,
                                                0
                                            )
                                            val pct = nextVol * 100 / maxVolume
                                            gestureBridge.onHint("音量 $pct%")
                                        }
                                        return true
                                    }
                                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                        parent?.requestDisallowInterceptTouchEvent(false)
                                        // 轻点侧边：显示一下播放器控制器
                                        if (!dragging) {
                                            playerView.performClick()
                                        }
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

                    val root = object : FrameLayout(ctx) {
                        val leftZone = sideGestureView(isBrightness = true)
                        val rightZone = sideGestureView(isBrightness = false)

                        init {
                            addView(
                                playerView,
                                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                            )
                            addView(leftZone)
                            addView(rightZone)
                        }

                        override fun onLayout(
                            changed: Boolean,
                            left: Int,
                            top: Int,
                            right: Int,
                            bottom: Int
                        ) {
                            super.onLayout(changed, left, top, right, bottom)
                            val w = right - left
                            val h = bottom - top
                            // 左右各 28%；上下留白，避免挡住返回键与进度条
                            val side = (w * 0.28f).toInt().coerceAtLeast(1)
                            val topPad = (56 * resources.displayMetrics.density).toInt()
                            val bottomPad = (72 * resources.displayMetrics.density).toInt()
                            val topY = topPad.coerceAtMost(h / 4)
                            val botY = (h - bottomPad).coerceAtLeast(h * 3 / 4)
                            leftZone.layout(0, topY, side, botY)
                            rightZone.layout(w - side, topY, w, botY)
                            leftZone.bringToFront()
                            rightZone.bringToFront()
                        }
                    }
                    root.tag = playerView
                    root
                },
                update = { root ->
                    val pv = root.tag as? PlayerView
                    pv?.player = player
                    pv?.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                },
                modifier = Modifier.fillMaxSize()
            )

            // 返回/全屏：始终置顶，横屏也可见可点
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .zIndex(20f)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
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
                    },
                    modifier = Modifier
                        .background(Color(0x99000000), RoundedCornerShape(22.dp))
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.White
                    )
                }
                IconButton(
                    onClick = { setFullscreen(!fullscreen) },
                    modifier = Modifier
                        .background(Color(0x99000000), RoundedCornerShape(22.dp))
                ) {
                    Icon(
                        if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                        contentDescription = if (fullscreen) "退出全屏" else "全屏",
                        tint = Color.White
                    )
                }
            }

            // 右下角设置：紧凑面板，竖屏/横屏都可用，不跳转整页
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .zIndex(25f)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(end = 12.dp, bottom = if (fullscreen) 20.dp else 10.dp)
            ) {
                if (showPlaySettings) {
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
                } else {
                    IconButton(
                        onClick = { showPlaySettings = true },
                        modifier = Modifier
                            .background(Color(0xCC000000), RoundedCornerShape(24.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(24.dp))
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "播放设置",
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
                        .zIndex(30f)
                        .background(Color(0xCC000000), RoundedCornerShape(12.dp))
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                )
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
            .widthIn(max = 280.dp)
            .background(Color(0xF012121A), RoundedCornerShape(14.dp))
            .border(1.dp, Color.White.copy(0.18f), RoundedCornerShape(14.dp))
            .padding(12.dp)
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
