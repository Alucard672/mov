package com.gofilm.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.gofilm.app.data.local.AppDatabase
import com.gofilm.app.data.local.PlayabilityCache
import com.gofilm.app.data.local.SettingsStore
import com.gofilm.app.data.local.TorrentMenuUnlock
import com.gofilm.app.data.repo.FilmRepository
import com.gofilm.app.data.repo.LocalRepository
import com.gofilm.app.data.repo.PlayUrlProber
import com.gofilm.app.data.stats.UserStatsReporter
import com.gofilm.app.data.torrent.TorrentDownloadStore
import okhttp3.OkHttpClient
import java.net.Proxy
import java.util.concurrent.TimeUnit

class GoFilmApp : Application(), ImageLoaderFactory {
    lateinit var settingsStore: SettingsStore
        private set
    lateinit var filmRepository: FilmRepository
        private set
    lateinit var localRepository: LocalRepository
        private set
    lateinit var playabilityCache: PlayabilityCache
        private set
    lateinit var playUrlProber: PlayUrlProber
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settingsStore = SettingsStore(this)
        filmRepository = FilmRepository(settingsStore)
        localRepository = LocalRepository(AppDatabase.get(this).appDao())
        playabilityCache = PlayabilityCache(this)
        playUrlProber = PlayUrlProber(playabilityCache)
        TorrentDownloadStore.init(this)
        // 预加载种子菜单解锁状态
        TorrentMenuUnlock.isUnlocked(this)
        // 用户统计：冷启动上报
        UserStatsReporter.reportOpen(this)
    }

    /**
     * Coil 默认会走系统代理；模拟器连不上本机 Clash(7890) 时海报全白。
     * 这里强制直连，并带浏览器 UA，减少源站防盗链拦截。
     */
    override fun newImageLoader(): ImageLoader {
        val okHttp = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"
                    )
                    .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                    .build()
                chain.proceed(req)
            }
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(okHttp)
            .crossfade(true)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(100L * 1024 * 1024)
                    .build()
            }
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .respectCacheHeaders(false)
            .build()
    }

    companion object {
        lateinit var instance: GoFilmApp
            private set
    }
}
